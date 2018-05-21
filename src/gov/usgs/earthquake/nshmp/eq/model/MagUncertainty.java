package gov.usgs.earthquake.nshmp.eq.model;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.StandardSystemProperty.LINE_SEPARATOR;
import static gov.usgs.earthquake.nshmp.internal.Parsing.addAttribute;
import static gov.usgs.earthquake.nshmp.internal.Parsing.addElement;
import static gov.usgs.earthquake.nshmp.internal.Parsing.toDoubleArray;
import static gov.usgs.earthquake.nshmp.internal.SourceAttribute.COUNT;
import static gov.usgs.earthquake.nshmp.internal.SourceAttribute.CUTOFF;
import static gov.usgs.earthquake.nshmp.internal.SourceAttribute.DELTAS;
import static gov.usgs.earthquake.nshmp.internal.SourceAttribute.MO_BALANCE;
import static gov.usgs.earthquake.nshmp.internal.SourceAttribute.SIGMA;
import static gov.usgs.earthquake.nshmp.internal.SourceAttribute.WEIGHTS;
import static gov.usgs.earthquake.nshmp.internal.SourceElement.ALEATORY;
import static gov.usgs.earthquake.nshmp.internal.SourceElement.EPISTEMIC;
import static gov.usgs.earthquake.nshmp.internal.SourceElement.MAG_UNCERTAINTY;

import java.util.Arrays;
import java.util.Map;

import org.w3c.dom.Element;

import gov.usgs.earthquake.nshmp.eq.Earthquakes;

/**
 * Wrapper class for magnitude uncertainty data. Uncertainty flags are
 * initialized based on input data, however, due to quirky nshmp rules, they may
 * be overriden at some point and should always be checked prior to calculation
 * regardless of any uncertainty values present.
 */
public class MagUncertainty {

  boolean hasEpistemic;
  int epiCount;
  double[] epiDeltas;
  double[] epiWeights;
  double epiCutoff;

  boolean hasAleatory;
  int aleaCount;
  double aleaSigma;
  boolean moBalance;
  double aleaCutoff;

  MagUncertainty() {}

  /**
   * Factory magnitude uncertainty container constructor.
   *
   * @param epiDeltas epistemic change to magnitude (M +/- delta)
   * @param epiWeights weight for each change; must be same length as
   *        {@code epiDeltas}
   * @param epiCutoff minimum magnitude for which epistemic uncertainty applies,
   *        below which it is disabled
   * @param aleaSigma standard deviation of aleatory Gaussian uncertainty
   * @param aleaCount number of aleatory uncertainty magnitude bins across a
   *        normal distribution
   * @param moBalance whether to preserve moment across aleatory uncertainty
   *        bins
   * @param aleaCutoff minimum magnitude for which aleatory uncertainty applies,
   *        below which it is disabled
   * @return a magnitude uncertainty container
   */
  public static MagUncertainty create(double[] epiDeltas, double[] epiWeights, double epiCutoff,
      double aleaSigma, int aleaCount, boolean moBalance, double aleaCutoff) {

    MagUncertainty mu = new MagUncertainty();

    checkArgument(epiDeltas.length > 0);
    checkArgument(epiWeights.length > 0);
    checkArgument(epiDeltas.length == epiWeights.length);
    mu.epiDeltas = epiDeltas;
    mu.epiWeights = epiWeights;
    mu.epiCount = mu.epiDeltas.length;
    mu.hasEpistemic = mu.epiCount > 1;
    mu.epiCutoff = Earthquakes.checkMagnitude(epiCutoff);

    checkArgument(aleaSigma >= 0);
    checkArgument(aleaCount < 40);
    mu.aleaSigma = aleaSigma;
    mu.aleaCount = aleaCount;
    mu.moBalance = moBalance;
    mu.hasAleatory = mu.aleaCount > 1 && mu.aleaSigma != 0.0;
    mu.aleaCutoff = Earthquakes.checkMagnitude(aleaCutoff);

    return mu;
  }

  /* Package-private constructor using XML attribute strings */
  static MagUncertainty create(Map<String, String> epiAtts, Map<String, String> aleaAtts) {

    MagUncertainty mu = new MagUncertainty();

    // epistemic
    if (epiAtts != null) {
      mu.epiDeltas = toDoubleArray(epiAtts.get(DELTAS.toString()));
      mu.epiWeights = toDoubleArray(epiAtts.get(WEIGHTS.toString()));
      mu.epiCutoff = Double.valueOf(epiAtts.get(CUTOFF.toString()));
      checkArgument(mu.epiDeltas.length == mu.epiWeights.length,
          "Epistemic deltas and mags are different lengths [%s, %s]", mu.epiDeltas.length,
          mu.epiWeights.length);
      mu.epiCount = mu.epiDeltas.length;
      mu.hasEpistemic = mu.epiCount > 1;
    }

    // aleatory
    if (aleaAtts != null) {
      mu.aleaSigma = Double.valueOf(aleaAtts.get(SIGMA.toString()));
      mu.aleaCount = Integer.valueOf(aleaAtts.get(COUNT.toString()));
      mu.aleaCutoff = Double.valueOf(aleaAtts.get(CUTOFF.toString()));
      checkArgument(mu.aleaCount % 2 == 1,
          "Aleatory bins [%s] should be odd so they center on mean magnitude", mu.aleaCount);
      mu.moBalance = Boolean.valueOf(aleaAtts.get(MO_BALANCE.toString()));
      // two ways to kill aleatory
      mu.hasAleatory = mu.aleaCount > 1 && mu.aleaSigma != 0.0;
    }

    return mu;
  }

  private static final String LF = LINE_SEPARATOR.value();

  @Override
  public String toString() {
    return new StringBuilder()
        .append("   MFD Data...").append(LF)
        .append("      Epistemic unc: ").append(hasEpistemic).append(LF)
        .append("             deltas: ").append(Arrays.toString(epiDeltas)).append(LF)
        .append("            weights: ").append(Arrays.toString(epiWeights)).append(LF)
        .append("             cutoff: ").append(epiCutoff).append(LF)
        .append("       Aleatory unc: ").append(hasAleatory).append(LF)
        .append("              sigma: ").append(aleaSigma).append(LF)
        .append("              count: ").append(aleaCount).append(LF)
        .append("         Mo balance: ").append(moBalance).append(LF)
        .append("             cutoff: ").append(aleaCutoff).append(LF).toString();
  }

  /**
   * Appends the XML form of this magnitude uncertainty data to the supplied
   * {@code Element}.
   * @param node to append to
   * @return a reference to the newly created {@code Element}
   */
  public Element appendTo(Element node) {
    if (!hasAleatory && !hasEpistemic) {
      return null;
    }
    Element e = addElement(MAG_UNCERTAINTY, node);
    if (hasEpistemic) {
      Element eEpistemic = addElement(EPISTEMIC, e);
      addAttribute(DELTAS, epiDeltas, eEpistemic);
      addAttribute(WEIGHTS, epiWeights, eEpistemic);
      addAttribute(CUTOFF, epiCutoff, eEpistemic);
    }
    if (hasAleatory) {
      Element eAleatory = addElement(ALEATORY, e);
      addAttribute(SIGMA, aleaSigma, eAleatory);
      addAttribute(COUNT, aleaCount, eAleatory);
      addAttribute(MO_BALANCE, moBalance, eAleatory);
      addAttribute(CUTOFF, aleaCutoff, eAleatory);
    }
    return e;
  }

}
