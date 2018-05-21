package gov.usgs.earthquake.nshmp.eq.model;

import static gov.usgs.earthquake.nshmp.eq.fault.FocalMech.NORMAL;
import static gov.usgs.earthquake.nshmp.eq.fault.FocalMech.REVERSE;
import static gov.usgs.earthquake.nshmp.eq.fault.FocalMech.STRIKE_SLIP;
import static gov.usgs.earthquake.nshmp.util.Maths.hypot;
import static java.lang.Math.ceil;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Ints;

import gov.usgs.earthquake.nshmp.data.XySequence;
import gov.usgs.earthquake.nshmp.eq.fault.FocalMech;
import gov.usgs.earthquake.nshmp.eq.fault.surface.RuptureScaling;
import gov.usgs.earthquake.nshmp.eq.fault.surface.RuptureSurface;
import gov.usgs.earthquake.nshmp.geo.Location;
import gov.usgs.earthquake.nshmp.geo.Locations;
import gov.usgs.earthquake.nshmp.util.Maths;

/**
 * Point-source earthquake {@code Source} supplies the simplest possible
 * representation of point-source {@code Rupture}s. When iterating, a
 * {@code PointSource} will supply {@code Rupture}s that provide dips and rakes
 * corresponding to different {@link FocalMech} types, but all distance metrics
 * are based on the site to point source location distance. This distance may be
 * corrected depending on choice of {@link RuptureScaling} model.
 *
 * <p><b>NOTE:</b> This source type should <i>not</i> be used in in conjunction
 * with ground motion models (GMMs) that consider hanging wall effects or
 * require more detailed distance metrics that are consistent with a
 * {@code Rupture}'s {@code FocalMech}, dip, and rake; use a
 * {@link PointSourceFinite} instead. This implementation throws an {code
 * UnsupportedOperationException} when such metrics are queried.
 *
 * <p><b>NOTE</b>: {@code PointSource}s are thread safe, however the
 * {@code Rupture}s returned by {@link Source#iterator()} are not.
 *
 * <p><b>NOTE</b>: {@link #size()} returns the absolute number of
 * {@code Rupture}s that can be created given the supplied source input
 * arguments; the iterator, however, <i>may</i> return fewer {@code Rupture}s as
 * some may have zero rates.
 *
 * @author Peter Powers
 */
class PointSource implements Source {

  final SourceType type;
  final Location loc;
  final XySequence mfd;
  final Map<FocalMech, Double> mechWtMap;
  final RuptureScaling rupScaling;
  final DepthModel depthModel;

  int rupCount;
  int magDepthSize;
  int ssIndex, revIndex;

  /**
   * Constructs a new point earthquake source. This is a simple model that does
   * not simulate finiteness (e.g. rupture rRup values will differ from rJB only
   * by virtue of the depth of the source).
   * 
   * @param type of source, as supplied from a parent {@code SourceSet}
   * @param loc <code>Location</code> of the point source
   * @param mfd magnitude frequency distribution of the source
   * @param mechWtMap <code>Map</code> of focal mechanism weights
   * @param rupScaling rupture scaling model that may, or may not, impose an rJB
   *        distance correction
   * @param depthModel specifies magnitude cutoffs and associated weights for
   *        different depth-to-top-of-ruptures
   */
  PointSource(
      SourceType type,
      Location loc,
      XySequence mfd,
      Map<FocalMech, Double> mechWtMap,
      RuptureScaling rupScaling,
      DepthModel depthModel) {

    this.type = type;
    this.loc = loc;
    this.mfd = mfd;
    this.mechWtMap = mechWtMap;
    this.rupScaling = rupScaling;
    this.depthModel = depthModel;
    init();
  }

  @Override
  public String name() {
    return "PointSource: " + formatLocation(loc);
  }

  static final String FORMAT = "%.3f, %.3f";

  static String formatLocation(Location loc) {
    return String.format(FORMAT, loc.lon(), loc.lat());
  }

  @Override
  public int size() {
    return rupCount;
  }

  /**
   * Overridden to return {@code -1}. Note that {@code PointSource}s may be
   * retrieved by index using {@link GridSourceSet#source(int)}.
   */
  @Override
  public int id() {
    return -1;
  }

  @Override
  public SourceType type() {
    return type;
  }

  /**
   * The location of the point source, irrespective of any distance corrections
   * that might be applied to attendant ruptures and ignoring the supplied site
   * {@code Location}.
   */
  @Override
  public Location location(Location site) {
    return loc;
  }

  @Override
  public List<XySequence> mfds() {
    return ImmutableList.of(mfd);
  }

  private void updateRupture(Rupture rup, int index) {

    int magDepthIndex = index % magDepthSize;
    int magIndex = depthModel.magDepthIndices.get(magDepthIndex);
    double mag = mfd.x(magIndex);
    double rate = mfd.y(magIndex);

    double zTop = depthModel.magDepthDepths.get(magDepthIndex);
    double zTopWt = depthModel.magDepthWeights.get(magDepthIndex);

    FocalMech mech = mechForIndex(index);
    double mechWt = mechWtMap.get(mech);

    rup.mag = mag;
    rup.rake = mech.rake();
    rup.rate = rate * zTopWt * mechWt;

    PointSurface pSurf = (PointSurface) rup.surface;
    pSurf.mag = mag; // KLUDGY needed for distance correction
    pSurf.dipRad = mech.dip() * Maths.TO_RAD;
    pSurf.zTop = zTop;

  }

  @Override
  public Iterator<Rupture> iterator() {
    return new Iterator<Rupture>() {
      Rupture rupture = new Rupture();
      {
        rupture.surface = new PointSurface(loc, rupScaling);
      }
      final int size = size();
      int caret = 0;

      @Override
      public boolean hasNext() {
        return caret < size;
      }

      @Override
      public Rupture next() {
        updateRupture(rupture, caret++);
        return rupture;
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException();
      }
    };
  }

  void init() {

    /*
     * Get the number of mag-depth iterations required to get to mMax. See
     * explanation in GridSourceSet for how magDepthIndices is set up
     */
    magDepthSize = depthModel.magDepthIndices.lastIndexOf(mfd.size() - 1) + 1;

    /*
     * Init rupture indexing: SS RV NR. Each category will have ruptures for
     * every mag in 'mfd' and depth in parent 'magDepthMap'.
     */
    int ssCount = (int) ceil(mechWtMap.get(STRIKE_SLIP)) * magDepthSize;
    int revCount = (int) ceil(mechWtMap.get(REVERSE)) * magDepthSize;
    int norCount = (int) ceil(mechWtMap.get(NORMAL)) * magDepthSize;
    ssIndex = ssCount;
    revIndex = ssCount + revCount;

    rupCount = ssCount + revCount + norCount;
  }

  /*
   * Returns the focal mechanism of the rupture at the supplied index.
   */
  FocalMech mechForIndex(int index) {
    // iteration order is always SS -> REV -> NOR
    return (index < ssIndex) ? STRIKE_SLIP : (index < revIndex) ? REVERSE : NORMAL;
  }

  static class PointSurface implements RuptureSurface {

    final Location loc;
    final RuptureScaling rupScaling;
    double mag;
    double dipRad;
    double zTop;

    PointSurface(Location loc, RuptureScaling rupScaling) {
      this.loc = loc;
      this.rupScaling = rupScaling;
    }

    @Override
    public Distance distanceTo(Location loc) {
      double rJB = Locations.horzDistanceFast(this.loc, loc);
      rJB = rupScaling.pointSourceDistance(mag, rJB);
      double rRup = hypot(rJB, zTop);
      return Distance.create(rJB, rRup, rJB);
    }

    /*
     * Width is needed to build GmmInputs, but is generally ignored by Gmms that
     * are capable of using point sources; a generic value of 10.0 km is
     * returned.
     */

    @Override
    public double strike() {
      throw new UnsupportedOperationException(exMessage("strike"));
    }

    @Override
    public double dip() {
      return dipRad * Maths.TO_DEG;
    }

    @Override
    public double dipRad() {
      return dipRad;
    }

    @Override
    public double dipDirection() {
      throw new UnsupportedOperationException(exMessage("dipDirection"));
    }

    @Override
    public double length() {
      throw new UnsupportedOperationException(exMessage("length"));
    }

    @Override
    public double width() {
      return 10.0; // km
    }

    @Override
    public double area() {
      throw new UnsupportedOperationException(exMessage("area"));
    }

    @Override
    public double depth() {
      return zTop;
    }

    @Override
    public Location centroid() {
      return loc;
    }

    private static String exMessage(String field) {
      return "No '" + field + "' for PointSource surface";
    }

  }

  /*
   * A depth model stores lookup arrays for mfd magnitude indexing, depths, and
   * depth weights. These arrays remove the need to do expensive lookups in a
   * magDepthMap when iterating grid sources and ruptures. A model may be longer
   * (have more magnitudes) than required by grid or area point source
   * implementations as it usually spans the [mMin mMax] of some master MFD.
   * Implementations will only ever reference those indices up to their
   * individual mMax so there should only be one per GridSourceSet or
   * AreaSource.
   *
   * Given magDepthMap:
   *
   * [6.5 :: [1.0:0.4, 3.0:0.5, 5.0:0.1]; 10.0 :: [1.0:0.1, 5.0:0.9]]
   *
   * and an MFD with mags:
   *
   * [5.0, 5.5, 6.0, 6.5, 7.0]
   *
   * The number of mag-depth combinations a point source would iterate over is:
   * sum(m = MFD.mag(i) * nDepths(m)) = 3 * 3 + 2 * 2 = 13
   *
   * (note: mag cutoffs in magDepthMap are always used as m < cutoff)
   *
   * magDepthIndices[] : magnitude index in original MFD
   *
   * [ 0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 4, 4]
   *
   * magDepthDepths[] : depth for index
   *
   * [1.0, 3.0, 5.0, 1.0, 3.0, 5.0, 1.0, 3.0, 5.0, 1.0, 5.0, 1.0, 5.0]
   *
   * magDepthWeights[] : depth weight for index
   *
   * [0.4, 0.5, 0.1, 0.4, 0.5, 0.1, 0.4, 0.5, 0.1, 0.1, 0.9, 0.1, 0.9]
   *
   * A depth model also encapsulates a maximum depth value that is usually
   * source type dependent and may be used when computing the maximum width of a
   * point source.
   *
   * All DepthModel validation is currently performed in GridSourceSet.Builder.
   */
  static final class DepthModel {

    /*
     * Initialized with a MagDepthMap; examples:
     *
     * single depth:
     *
     * [10.0 :: [depth : 1.0 ]]
     *
     * NSHMP depths:
     *
     * [6.5 :: [1.0 : 0.0, 5.0 : 1.0], 10.0 :: [1.0 : 1.0, 5.0 : 0.0]]
     */

    /*
     * maxDepth constrains the width of finite point sources. In many cases
     * (e.g. CEUS) this is not used as sources are simply modeled as lines; the
     * gmm's do not require a full finite-source parameterization.
     */
    final double maxDepth;

    final List<Double> magMaster;

    final List<Integer> magDepthIndices;
    final List<Double> magDepthDepths;
    final List<Double> magDepthWeights;

    static DepthModel create(
        NavigableMap<Double, Map<Double, Double>> magDepthMap,
        List<Double> magMaster,
        double maxDepth) {

      return new DepthModel(magDepthMap, magMaster, maxDepth);
    }

    private DepthModel(
        NavigableMap<Double, Map<Double, Double>> magDepthMap,
        List<Double> magMaster,
        double maxDepth) {

      this.magMaster = magMaster;
      this.maxDepth = maxDepth;

      List<Integer> indices = Lists.newArrayList();
      List<Double> depths = Lists.newArrayList();
      List<Double> weights = Lists.newArrayList();

      for (int i = 0; i < magMaster.size(); i++) {
        Map.Entry<Double, Map<Double, Double>> magEntry =
            magDepthMap.higherEntry(magMaster.get(i));
        for (Map.Entry<Double, Double> entry : magEntry.getValue().entrySet()) {
          indices.add(i);
          depths.add(entry.getKey());
          weights.add(entry.getValue());
        }
      }

      magDepthIndices = Ints.asList(Ints.toArray(indices));
      magDepthDepths = Doubles.asList(Doubles.toArray(depths));
      magDepthWeights = Doubles.asList(Doubles.toArray(weights));
    }
  }

}
