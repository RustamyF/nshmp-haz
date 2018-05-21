package gov.usgs.earthquake.nshmp.eq.model;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.StandardSystemProperty.LINE_SEPARATOR;
import static gov.usgs.earthquake.nshmp.eq.model.SourceType.CLUSTER;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import gov.usgs.earthquake.nshmp.data.XySequence;
import gov.usgs.earthquake.nshmp.geo.Location;
import gov.usgs.earthquake.nshmp.geo.LocationList;
import gov.usgs.earthquake.nshmp.geo.Locations;
import gov.usgs.earthquake.nshmp.mfd.IncrementalMfd;

/**
 * Cluster source representation. Each cluster source wraps a
 * {@code FaultSourceSet} containing one or more fault representations that
 * rupture as independent events but with a similar rate. For example, at New
 * Madrid, each ClusterSourceSet has 5 ClusterSources, one for each position
 * variant of the model. For each position variant there is one FaultSourceSet
 * containing the FaultSources in the cluster, each of which may have one, or
 * more, magnitude or other variants represented by its internal list of
 * {@code IncrementalMfd}s.
 *
 * <p>Cluster source hazard is calculated from the joint probabilities of ground
 * motions from the wrapped faults, which is handled internally by a separate
 * calculator and {@link ClusterSource#iterator()} therefore throws an
 * {@code UnsupportedOperationException}.
 *
 * <p>Unlike other {@code Source}s whose weights are carried exclusively with
 * their associated {@link IncrementalMfd}, {@code ClusterSource}s carry an
 * additional {@link #weight()} value.
 *
 * <p>A {@code ClusterSource} cannot be created directly; it may only be created
 * by a private parser.
 *
 * @author Peter Powers
 */
public class ClusterSource implements Source {

  final double rate; // from the default mfd xml
  final FaultSourceSet faults;

  ClusterSource(double rate, FaultSourceSet faults) {
    this.rate = rate;
    this.faults = faults;
  }

  @Override
  public String name() {
    return faults.name();
  }

  @Override
  public int size() {
    return faults.size();
  }

  @Override
  public int id() {
    return faults.id();
  }

  @Override
  public SourceType type() {
    return CLUSTER;
  }

  /**
   * The closest point across the traces of all fault sources that participate
   * in this cluster, relative to the supplied site {@code Location}.
   */
  @Override
  public Location location(Location site) {
    LocationList.Builder locs = LocationList.builder();
    for (FaultSource fault : faults) {
      locs.add(fault.location(site));
    }
    return Locations.closestPoint(site, locs.build());
  }

  @Override
  public List<XySequence> mfds() {
    ImmutableList.Builder<XySequence> xyMfds = ImmutableList.builder();
    for (FaultSource fault : faults) {
      for (XySequence mfd : fault.mfds()) {
        mfd.multiply(rate);
        xyMfds.add(mfd);
      }
    }
    return xyMfds.build();
  }

  /**
   * {@code (1 / return period)} of this source in years.
   * @return the cluster rate
   */
  public double rate() {
    return rate;
  }

  /**
   * The weight applicable to this {@code ClusterSource}.
   */
  public double weight() {
    return faults.weight();
  }

  /**
   * The {@code FaultSourceSet} of all {@code FaultSource}s that participate in
   * this cluster.
   */
  public FaultSourceSet faults() {
    return faults;
  }

  /**
   * Overriden to throw an {@code UnsupportedOperationException}. Cluster
   * sources are handled differently than other source types.
   */
  @Override
  public Iterator<Rupture> iterator() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String toString() {
    Map<Object, Object> data = ImmutableMap.builder()
        .put("name", name())
        .put("rate", rate())
        .put("weight", weight())
        .build();
    StringBuilder sb = new StringBuilder()
        .append(getClass().getSimpleName())
        .append(" ")
        .append(data)
        .append(LINE_SEPARATOR.value());
    for (FaultSource fs : faults) {
      sb.append("  ")
          .append(fs.toString())
          .append(LINE_SEPARATOR.value());
    }
    return sb.toString();
  }

  /* Single use builder */
  static class Builder {

    static final String ID = "ClusterSource.Builder";
    boolean built = false;

    Double rate;
    FaultSourceSet faults;

    Builder rate(double rate) {
      // TODO what sort of value checking should be done for rate (<1 ??)
      this.rate = rate;
      return this;
    }

    Builder faults(FaultSourceSet faults) {
      checkState(checkNotNull(faults, "Fault source set is null").size() > 0,
          "Fault source set is empty");
      this.faults = faults;
      return this;
    }

    void validateState(String source) {
      checkState(!built, "This %s instance as already been used", source);
      checkState(rate != null, "%s rate not set", source);
      checkState(faults != null, "%s has no fault sources", source);
      built = true;
    }

    ClusterSource buildClusterSource() {
      validateState(ID);
      return new ClusterSource(rate, faults);
    }
  }

}
