package gov.usgs.earthquake.nshmp.eq.model;

import static gov.usgs.earthquake.nshmp.eq.fault.FocalMech.NORMAL;
import static gov.usgs.earthquake.nshmp.eq.fault.FocalMech.REVERSE;
import static gov.usgs.earthquake.nshmp.eq.fault.FocalMech.STRIKE_SLIP;
import static gov.usgs.earthquake.nshmp.util.Maths.hypot;
import static java.lang.Math.ceil;
import static java.lang.Math.cos;
import static java.lang.Math.min;
import static java.lang.Math.sin;
import static java.lang.Math.tan;

import java.util.Iterator;
import java.util.Map;

import gov.usgs.earthquake.nshmp.data.XySequence;
import gov.usgs.earthquake.nshmp.eq.fault.FocalMech;
import gov.usgs.earthquake.nshmp.eq.fault.surface.RuptureScaling;
import gov.usgs.earthquake.nshmp.geo.Location;
import gov.usgs.earthquake.nshmp.geo.Locations;
import gov.usgs.earthquake.nshmp.util.Maths;

/**
 * Point-source earthquake implementation in which all magnitudes are
 * represented as finite faults and any normal or reverse sources are
 * represented with two possible geometries, one dipping towards the observer
 * and one dipping away. In both cases the leading edge of the finite source
 * representation is located at the point {@code Location} of the source itself
 * (in one representation the bottom trace is at the point {@code Location} and
 * the fault dips towards the observer, in its complement the top trace is at
 * the point {@code Location} and the fault dips away from the observer; TODO
 * add illustration or link).
 *
 * <p>This is the generalized point earthquake source representation used for
 * the 2014 NSHMP. It was created to provide support for weighted
 * magnitude-depth distributions and improved approximations of hanging wall
 * terms vis-a-vis self-consistent distance calculations.
 *
 * <p><b>NOTE</b>: See {@link PointSource} description for notes on thread
 * safety and {@code Rupture} creation and iteration.
 *
 * @author Peter Powers
 */
class PointSourceFinite extends PointSource {

  int fwIndexLo, fwIndexHi;

  /**
   * Constructs a new point earthquake source that provides ruptures will
   * simulate finite fault parameterizations such as hanging-wall effects.
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
  PointSourceFinite(
      SourceType type,
      Location loc,
      XySequence mfd,
      Map<FocalMech, Double> mechWtMap,
      RuptureScaling rupScaling,
      DepthModel depthModel) {

    super(type, loc, mfd, mechWtMap, rupScaling, depthModel);
    init();
  }

  @Override
  public String name() {
    return "PointSourceFinite: " + formatLocation(loc);
  }

  /*
   * NOTE/TODO: Although there should not be many instances where a
   * PointSourceFinite rupture rate is reduced to zero (a mag-depth weight [this
   * is not curently checked] of an MFD rate could be zero), in the cases where
   * it is, we're doing a little more work than necessary below. We could
   * alternatively short-circuit updateRupture() this method to return null
   * reference but don't like returning null.
   */

  private void updateRupture(Rupture rup, int index) {

    int magDepthIndex = index % magDepthSize;
    int magIndex = depthModel.magDepthIndices.get(magDepthIndex);
    double mag = mfd.x(magIndex);
    double rate = mfd.y(magIndex);

    double zTop = depthModel.magDepthDepths.get(magDepthIndex);
    double zTopWt = depthModel.magDepthWeights.get(magDepthIndex);

    FocalMech mech = mechForIndex(index);
    double mechWt = mechWtMap.get(mech);
    if (mech != STRIKE_SLIP) {
      mechWt *= 0.5;
    }
    double dipRad = mech.dip() * Maths.TO_RADIANS;

    double maxWidthDD = (depthModel.maxDepth - zTop) / sin(dipRad);
    double widthDD = rupScaling.dimensions(mag, maxWidthDD).width;

    rup.mag = mag;
    rup.rake = mech.rake();
    rup.rate = rate * zTopWt * mechWt;

    FiniteSurface fpSurf = (FiniteSurface) rup.surface;
    fpSurf.mag = mag; // KLUDGY needed for distance correction
    fpSurf.dipRad = dipRad;
    fpSurf.widthDD = widthDD;
    fpSurf.widthH = widthDD * cos(dipRad);
    fpSurf.zTop = zTop;
    fpSurf.zBot = zTop + widthDD * sin(dipRad);
    fpSurf.footwall = isOnFootwall(index);
  }

  @Override
  public Iterator<Rupture> iterator() {
    return new Iterator<Rupture>() {
      Rupture rupture = new Rupture();
      {
        rupture.surface = new FiniteSurface(loc, rupScaling);
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

  @Override
  void init() {

    /*
     * Get the number of mag-depth iterations required to get to mMax. See
     * explanation in GridSourceSet for how magDepthIndices is set up
     */
    magDepthSize = depthModel.magDepthIndices.lastIndexOf(mfd.size() - 1) + 1;

    /*
     * Init rupture indexing: SS-FW RV-FW RV-HW NR-FW NR-HW. Each category will
     * have ruptures for every mag in 'mfd' and depth in parent 'magDepthMap'.
     */
    int ssCount = (int) ceil(mechWtMap.get(STRIKE_SLIP)) * magDepthSize;
    int revCount = (int) ceil(mechWtMap.get(REVERSE)) * magDepthSize * 2;
    int norCount = (int) ceil(mechWtMap.get(NORMAL)) * magDepthSize * 2;
    ssIndex = ssCount;
    revIndex = ssCount + revCount;
    fwIndexLo = ssCount + revCount / 2;
    fwIndexHi = ssCount + revCount + norCount / 2;

    rupCount = ssCount + revCount + norCount;
  }

  /*
   * Returns whether the rupture at index should be on the footwall (i.e. have
   * its rX value set negative). Strike-slip mechs are marked as footwall to
   * potentially short circuit GMPE calcs. Because the index order is SS-FW
   * RV-FW RV-HW NR-FW NR-HW
   */
  boolean isOnFootwall(int index) {
    return (index < fwIndexLo)
        ? true : (index < revIndex)
            ? false : (index < fwIndexHi)
                ? true : false;
  }

  static class FiniteSurface extends PointSurface {

    double zBot; // base of rupture; may be less than 14km
    double widthH; // horizontal width (surface projection)
    double widthDD; // down-dip width
    boolean footwall;

    FiniteSurface(Location loc, RuptureScaling rupScaling) {
      super(loc, rupScaling);
    }

    @Override
    public Distance distanceTo(Location loc) {
      double rJB = Locations.horzDistanceFast(this.loc, loc);
      rJB = rupScaling.pointSourceDistance(mag, rJB);
      double rX = footwall ? -rJB : rJB + widthH;

      if (footwall) {
        return Distance.create(rJB, hypot(rJB, zTop), rX);
      }

      double rCut = zBot * tan(dipRad);

      if (rJB > rCut) {
        return Distance.create(rJB, hypot(rJB, zBot), rX);
      }

      // rRup when rJB is 0 -- we take the minimum the site-to-top-edge
      // and site-to-normal of rupture for the site being directly over
      // the down-dip edge of the rupture
      double rRup0 = min(hypot(widthH, zTop), zBot * cos(dipRad));
      // rRup at cutoff rJB
      double rRupC = zBot / cos(dipRad);
      // scale linearly with rJB distance
      double rRup = (rRupC - rRup0) * rJB / rCut + rRup0;

      return Distance.create(rJB, rRup, rX);
    }

    @Override
    public double width() {
      return widthDD;
    }

  }

}
