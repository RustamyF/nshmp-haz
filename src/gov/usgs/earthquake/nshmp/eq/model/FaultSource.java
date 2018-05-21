package gov.usgs.earthquake.nshmp.eq.model;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static gov.usgs.earthquake.nshmp.data.Data.checkInRange;
import static gov.usgs.earthquake.nshmp.eq.Earthquakes.checkCrustalDepth;
import static gov.usgs.earthquake.nshmp.eq.Earthquakes.checkCrustalWidth;
import static gov.usgs.earthquake.nshmp.eq.fault.Faults.checkDip;
import static gov.usgs.earthquake.nshmp.eq.fault.Faults.checkRake;
import static gov.usgs.earthquake.nshmp.eq.fault.Faults.checkTrace;
import static gov.usgs.earthquake.nshmp.eq.model.SourceType.FAULT;
import static gov.usgs.earthquake.nshmp.internal.TextUtils.validateName;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Range;

import gov.usgs.earthquake.nshmp.data.XySequence;
import gov.usgs.earthquake.nshmp.eq.fault.surface.DefaultGriddedSurface;
import gov.usgs.earthquake.nshmp.eq.fault.surface.GriddedSurface;
import gov.usgs.earthquake.nshmp.eq.fault.surface.RuptureFloating;
import gov.usgs.earthquake.nshmp.eq.fault.surface.RuptureScaling;
import gov.usgs.earthquake.nshmp.geo.Location;
import gov.usgs.earthquake.nshmp.geo.LocationList;
import gov.usgs.earthquake.nshmp.geo.Locations;
import gov.usgs.earthquake.nshmp.mfd.IncrementalMfd;

/**
 * Fault source representation. This class wraps a model of a fault geometry and
 * a list of magnitude frequency distributions that characterize how the fault
 * might rupture (e.g. as one, single geometry-filling event, or as multiple
 * smaller events) during earthquakes. Smaller events are modeled as 'floating'
 * ruptures; they occur in multiple locations on the fault surface with
 * appropriately scaled rates.
 *
 * <p>A {@code FaultSource} cannot be created directly; it may only be created
 * by a private parser.
 *
 * @author Peter Powers
 */
public class FaultSource implements Source {

  final String name;
  final int id;
  final LocationList trace;
  final double dip;
  final double width;
  final double rake;
  final List<IncrementalMfd> mfds;
  final double spacing;
  final RuptureScaling rupScaling;
  final RuptureFloating rupFloating;
  final boolean rupVariability;
  final GriddedSurface surface;

  private final List<List<Rupture>> ruptureLists; // 1:1 with Mfds

  // package privacy for subduction subclass
  FaultSource(
      String name,
      int id,
      LocationList trace,
      double dip,
      double width,
      GriddedSurface surface,
      double rake,
      List<IncrementalMfd> mfds,
      double spacing,
      RuptureScaling rupScaling,
      RuptureFloating rupFloating,
      boolean rupVariability) {

    this.name = name;
    this.id = id;
    this.trace = trace;
    this.dip = dip;
    this.width = width;
    this.surface = surface;
    this.rake = rake;
    this.mfds = mfds;
    this.spacing = spacing;
    this.rupScaling = rupScaling;
    this.rupFloating = rupFloating;
    this.rupVariability = rupVariability;

    ruptureLists = initRuptureLists();
    checkState(Iterables.size(Iterables.concat(ruptureLists)) > 0,
        "FaultSource has no ruptures");
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public int size() {
    return Iterables.size(this);
  }

  @Override
  public int id() {
    return id;
  }

  @Override
  public SourceType type() {
    return FAULT;
  }

  /**
   * The closest point on the fault trace, relative to the supplied site
   * {@code Location}.
   */
  @Override
  public Location location(Location site) {
    return Locations.closestPoint(site, trace);
  }

  @Override
  public List<XySequence> mfds() {
    /*
     * TODO this should just pass a reference to the immutable list or map (once
     * tracking logic-tree branches). Requires MFD implementation update
     */
    ImmutableList.Builder<XySequence> xyMfds = ImmutableList.builder();
    for (IncrementalMfd mfd : mfds) {
      xyMfds.add(XySequence.create(mfd.xValues(), mfd.yValues()));
    }
    return xyMfds.build();
  }

  @Override
  public Iterator<Rupture> iterator() {
    return Iterables.concat(ruptureLists).iterator();
  }

  @Override
  public String toString() {
    Map<Object, Object> data = ImmutableMap.builder()
        .put("name", name)
        .put("dip", dip)
        .put("width", width)
        .put("rake", rake)
        .put("mfds", mfds.size())
        .put("top", trace.first().depth())
        .build();
    return getClass().getSimpleName() + " " + data;
  }

  private List<List<Rupture>> initRuptureLists() {
    ImmutableList.Builder<List<Rupture>> rupListsBuilder = ImmutableList.builder();
    for (IncrementalMfd mfd : mfds) {
      List<Rupture> rupList = createRuptureList(mfd);
      checkState(rupList.size() > 0, "Rupture list is empty");
      rupListsBuilder.add(rupList);
    }
    return rupListsBuilder.build();
  }

  private List<Rupture> createRuptureList(IncrementalMfd mfd) {
    ImmutableList.Builder<Rupture> rupListbuilder = ImmutableList.builder();

    for (int i = 0; i < mfd.getNum(); ++i) {
      double mag = mfd.getX(i);
      double rate = mfd.getY(i);

      // TODO do we really want to do this??
      // TODO low rate shortcut should be derived from config
      // and applied when building input lists
      if (rate < 1e-14) {
        continue; // shortcut low rates
      }

      // TODO we want to get the 'floats' attribute out of MFDs
      // the only reason it is there is to allow SINGLE to flip-flop
      // it should just be a SourceProperty
      if (mfd.floats()) {

        // AbstractGriddedSurface surf = (AbstractGriddedSurface)
        // surface;
        //
        List<Rupture> floaters = rupFloating.createFloatingRuptures(
            surface, rupScaling, mag, rate, rake, rupVariability);
        rupListbuilder.addAll(floaters);

      } else {
        Rupture rup = Rupture.create(mag, rate, rake, surface);
        rupListbuilder.add(rup);
      }
    }
    return rupListbuilder.build();
  }

  /* Single use builder */
  static class Builder {

    private static final String ID = "FaultSource.Builder";
    private boolean built = false;

    private static final Range<Double> SURFACE_GRID_SPACING_RANGE = Range.closed(0.01, 20.0);

    // required
    String name;
    Integer id;
    LocationList trace;
    Double dip;
    Double width;
    Double depth;
    Double rake;
    ImmutableList.Builder<IncrementalMfd> mfdsBuilder = ImmutableList.builder();
    List<IncrementalMfd> mfds;
    Double spacing;
    RuptureScaling rupScaling;
    RuptureFloating rupFloating;
    Boolean rupVariability;

    Builder name(String name) {
      this.name = validateName(name);
      return this;
    }

    Builder id(int id) {
      this.id = id;
      return this;
    }

    Builder trace(LocationList trace) {
      this.trace = checkTrace(trace);
      return this;
    }

    Builder dip(double dip) {
      this.dip = checkDip(dip);
      return this;
    }

    Builder width(double width) {
      this.width = checkCrustalWidth(width);
      return this;
    }

    Builder depth(double depth) {
      this.depth = checkCrustalDepth(depth);
      return this;
    }

    Builder rake(double rake) {
      this.rake = checkRake(rake);
      return this;
    }

    Builder mfd(IncrementalMfd mfd) {
      this.mfdsBuilder.add(checkNotNull(mfd, "MFD is null"));
      return this;
    }

    Builder mfds(List<IncrementalMfd> mfds) {
      checkNotNull(mfds, "MFD list is null");
      checkArgument(mfds.size() > 0, "MFD list is empty");
      this.mfdsBuilder.addAll(mfds);
      return this;
    }

    Builder surfaceSpacing(double spacing) {
      this.spacing = checkInRange(SURFACE_GRID_SPACING_RANGE, "Floater Offset", spacing);
      return this;
    }

    Builder ruptureScaling(RuptureScaling rupScaling) {
      this.rupScaling = checkNotNull(rupScaling, "Rup-Scaling Relation is null");
      return this;
    }

    Builder ruptureFloating(RuptureFloating rupFloating) {
      this.rupFloating = checkNotNull(rupFloating, "Rup-Floating Model is null");
      return this;
    }

    Builder ruptureVariability(boolean rupVariability) {
      this.rupVariability = rupVariability;
      return this;
    }

    void validateState(String buildId) {
      checkState(!built, "This %s instance as already been used", buildId);
      checkState(name != null, "%s name not set", buildId);
      checkState(id != null, "%s id not set", buildId);
      checkState(trace != null, "%s trace not set", buildId);
      checkState(dip != null, "%s dip not set", buildId);
      checkState(width != null, "%s width not set", buildId);
      checkState(depth != null, "%s depth not set", buildId);
      checkState(rake != null, "%s rake not set", buildId);
      checkState(mfds.size() > 0, "%s has no MFDs", buildId);
      checkState(spacing != null, "%s surface grid spacing not set", buildId);
      checkState(rupScaling != null, "%s rupture-scaling relation not set", buildId);
      checkState(rupFloating != null, "%s rupture-floating model not set", buildId);
      checkState(rupVariability != null, "%s rupture-area variability flag not set", buildId);
      built = true;
    }

    FaultSource buildFaultSource() {

      mfds = mfdsBuilder.build();

      validateState(ID);

      // create surface
      DefaultGriddedSurface surface = DefaultGriddedSurface.builder().trace(trace)
          .depth(depth).dip(dip).width(width).spacing(spacing).build();

      return new FaultSource(name, id, trace, dip, width, surface, rake,
          ImmutableList.copyOf(mfds), spacing, rupScaling, rupFloating, rupVariability);
    }
  }

}
