package gov.usgs.earthquake.nshmp.eq.model;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.StandardSystemProperty.LINE_SEPARATOR;
import static gov.usgs.earthquake.nshmp.eq.Earthquakes.checkInterfaceDepth;
import static gov.usgs.earthquake.nshmp.eq.Earthquakes.checkInterfaceWidth;
import static gov.usgs.earthquake.nshmp.eq.fault.Faults.checkTrace;
import static gov.usgs.earthquake.nshmp.eq.model.SourceType.INTERFACE;

import java.util.List;

import com.google.common.collect.ImmutableList;

import gov.usgs.earthquake.nshmp.eq.fault.surface.ApproxGriddedSurface;
import gov.usgs.earthquake.nshmp.eq.fault.surface.DefaultGriddedSurface;
import gov.usgs.earthquake.nshmp.eq.fault.surface.GriddedSurface;
import gov.usgs.earthquake.nshmp.eq.fault.surface.RuptureFloating;
import gov.usgs.earthquake.nshmp.eq.fault.surface.RuptureScaling;
import gov.usgs.earthquake.nshmp.geo.Location;
import gov.usgs.earthquake.nshmp.geo.LocationList;
import gov.usgs.earthquake.nshmp.geo.Locations;
import gov.usgs.earthquake.nshmp.mfd.IncrementalMfd;

/**
 * Subduction source representation. This class wraps a model of a subduction
 * interface geometry and a list of magnitude frequency distributions that
 * characterize how the fault might rupture (e.g. as one, single
 * geometry-filling event, or as multiple smaller events) during earthquakes.
 * Smaller events are modeled as 'floating' ruptures; they occur in multiple
 * locations on the fault surface with appropriately scaled rates.
 *
 * <p>An {@code InterfaceSource} cannot be created directly; it may only be
 * created by a private parser.
 *
 * @author Peter Powers
 */
public class InterfaceSource extends FaultSource {

  final LocationList lowerTrace;

  private InterfaceSource(
      String name,
      int id,
      LocationList upperTrace,
      LocationList lowerTrace,
      double dip,
      double width,
      GriddedSurface surface,
      double rake,
      List<IncrementalMfd> mfds,
      double spacing,
      RuptureScaling rupScaling,
      RuptureFloating rupFloating,
      boolean rupVariability) {

    super(name, id, upperTrace, dip, width, surface, rake, mfds, spacing, rupScaling,
        rupFloating,
        rupVariability);

    this.lowerTrace = (lowerTrace == null) ? surface.getEvenlyDiscritizedLowerEdge()
        : lowerTrace;

    // TODO lowerTrace may be null and this is bad bad; lowerTrace
    // is referenced in InterfaceSourceSet distanceFilter and
    // we should populate this even if the original source only
    // specified an upper trace. This highlights another shortcoming
    // of Container2D and GriddedSurface: why is there no getRow(int)
    // of getBottomRow() given that there is a getUpperEdge(),
    // acknowledging that the latter may not be the same as the trace
    // due to seismogenic depth constraints. For now, we are ignoring
    // lower trace in distance filter, but given large width of interface
    // sources TODO clean up Container2D methods

  }

  @Override
  public SourceType type() {
    return INTERFACE;
  }

  /**
   * The closest point on the upper or lower fault trace, relative to the
   * supplied site {@code Location}.
   */
  @Override
  public Location location(Location site) {
    return Locations.closestPoint(site,
        LocationList.builder()
            .addAll(trace)
            .addAll(lowerTrace)
            .build());
  }

  @Override
  public String toString() {
    // TODO use joiner
    return new StringBuilder()
        .append("==========  Interface Source  ==========")
        .append(LINE_SEPARATOR.value())
        .append("  Source name: ").append(name())
        .append(LINE_SEPARATOR.value())
        .append("          dip: ").append(dip)
        .append(LINE_SEPARATOR.value())
        .append("        width: ").append(width)
        .append(LINE_SEPARATOR.value())
        .append("         rake: ").append(rake)
        .append(LINE_SEPARATOR.value())
        .append("         mfds: ").append(mfds.size())
        .append(LINE_SEPARATOR.value())
        .append("          top: ").append(trace.first().depth())
        .append(LINE_SEPARATOR.value()).toString();
  }

  /* Single use builder. */
  static class Builder extends FaultSource.Builder {

    private static final String ID = "InterfaceSource.Builder";

    // required
    private LocationList lowerTrace;

    @Override
    Builder depth(double depth) {
      this.depth = checkInterfaceDepth(depth);
      return this;
    }

    @Override
    Builder width(double width) {
      this.width = checkInterfaceWidth(width);
      return this;
    }

    Builder lowerTrace(LocationList trace) {
      checkNotNull(this.trace, "Upper trace must be set first");
      this.lowerTrace = checkTrace(trace);
      return this;
    }

    InterfaceSource buildSubductionSource() {

      /*
       * Either upper and lower trace must be set, or upper trace depth, dip,
       * and width. If upper and lower are set, depth, dip and width (if also
       * set) will ultimately be ignored and computed lazily by the subduction
       * surface implementation; otherwise the three fields are set to NaN to
       * satisfy parent builder.
       */

      mfds = mfdsBuilder.build();

      GriddedSurface surface = null;

      if (lowerTrace != null) {

        // if going dual-trace route, satisfy parent builder with NaNs
        this.depth = Double.NaN;
        this.dip = Double.NaN;
        this.width = Double.NaN;
        validateState(ID);
        surface = new ApproxGriddedSurface(trace, lowerTrace, spacing);

      } else {

        // otherwise build a basic fault source
        validateState(ID);
        surface = DefaultGriddedSurface.builder()
            .trace(trace)
            .depth(depth)
            .dip(dip)
            .width(width)
            .spacing(spacing)
            .build();
      }

      return new InterfaceSource(name, id, trace, lowerTrace, dip, width, surface, rake,
          ImmutableList.copyOf(mfds), spacing, rupScaling, rupFloating, rupVariability);
    }

  }

}
