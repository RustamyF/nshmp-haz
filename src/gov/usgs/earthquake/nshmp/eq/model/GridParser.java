package gov.usgs.earthquake.nshmp.eq.model;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static gov.usgs.earthquake.nshmp.eq.model.SourceType.GRID;
import static gov.usgs.earthquake.nshmp.internal.Parsing.readDouble;
import static gov.usgs.earthquake.nshmp.internal.Parsing.readEnum;
import static gov.usgs.earthquake.nshmp.internal.Parsing.readInt;
import static gov.usgs.earthquake.nshmp.internal.Parsing.readString;
import static gov.usgs.earthquake.nshmp.internal.Parsing.stringToEnumWeightMap;
import static gov.usgs.earthquake.nshmp.internal.Parsing.stringToValueValueWeightMap;
import static gov.usgs.earthquake.nshmp.internal.SourceAttribute.C_MAG;
import static gov.usgs.earthquake.nshmp.internal.SourceAttribute.FOCAL_MECH_MAP;
import static gov.usgs.earthquake.nshmp.internal.SourceAttribute.ID;
import static gov.usgs.earthquake.nshmp.internal.SourceAttribute.MAG_DEPTH_MAP;
import static gov.usgs.earthquake.nshmp.internal.SourceAttribute.MAX_DEPTH;
import static gov.usgs.earthquake.nshmp.internal.SourceAttribute.NAME;
import static gov.usgs.earthquake.nshmp.internal.SourceAttribute.PATH;
import static gov.usgs.earthquake.nshmp.internal.SourceAttribute.RUPTURE_SCALING;
import static gov.usgs.earthquake.nshmp.internal.SourceAttribute.STRIKE;
import static gov.usgs.earthquake.nshmp.internal.SourceAttribute.TYPE;
import static gov.usgs.earthquake.nshmp.internal.SourceAttribute.WEIGHT;
import static java.util.logging.Level.FINE;

import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.DefaultHandler;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.logging.Logger;

import javax.xml.parsers.SAXParser;

import gov.usgs.earthquake.nshmp.data.Data;
import gov.usgs.earthquake.nshmp.data.XySequence;
import gov.usgs.earthquake.nshmp.eq.Earthquakes;
import gov.usgs.earthquake.nshmp.eq.fault.FocalMech;
import gov.usgs.earthquake.nshmp.eq.fault.surface.RuptureScaling;
import gov.usgs.earthquake.nshmp.eq.model.MfdHelper.GR_Data;
import gov.usgs.earthquake.nshmp.eq.model.MfdHelper.IncrData;
import gov.usgs.earthquake.nshmp.eq.model.MfdHelper.SingleData;
import gov.usgs.earthquake.nshmp.eq.model.MfdHelper.TaperData;
import gov.usgs.earthquake.nshmp.geo.Location;
import gov.usgs.earthquake.nshmp.internal.Parsing;
import gov.usgs.earthquake.nshmp.internal.Parsing.Delimiter;
import gov.usgs.earthquake.nshmp.internal.SourceElement;
import gov.usgs.earthquake.nshmp.mfd.IncrementalMfd;
import gov.usgs.earthquake.nshmp.mfd.MfdType;
import gov.usgs.earthquake.nshmp.mfd.Mfds;

/*
 * Non-validating grid source parser. SAX parser 'Attributes' are stateful and
 * cannot be stored. This class is not thread safe.
 *
 * @author Peter Powers
 */
@SuppressWarnings("incomplete-switch")
class GridParser extends DefaultHandler {

  static final String RATE_DIR = "sources";

  private final Logger log = Logger.getLogger(GridParser.class.getName());
  private final SAXParser sax;
  private boolean used = false;

  private Locator locator;

  // Default MFD data
  private boolean externalRateFile = false;
  private boolean parsingDefaultMFDs = false;
  private MfdHelper.Builder mfdHelperBuilder;
  private MfdHelper mfdHelper;

  private GmmSet gmmSet;

  private ModelConfig config;

  private GridSourceSet sourceSet;
  private GridSourceSet.Builder sourceSetBuilder;

  // master magnitude list data
  private double minMag = Earthquakes.MAG_RANGE.upperEndpoint();
  private double maxMag = Earthquakes.MAG_RANGE.lowerEndpoint();
  private double deltaMag;

  // Node locations are the only text content in source files
  private boolean readingLoc = false;
  private StringBuilder locBuilder = null;

  // Per-node MFD and mechMap
  private XySequence nodeMFD = null;
  private Map<FocalMech, Double> nodeMechMap = null;

  // Exposed for use when validating depths in subclasses
  SourceType type = GRID;

  private GridParser(SAXParser sax) {
    this.sax = checkNotNull(sax);
  }

  static GridParser create(SAXParser sax) {
    return new GridParser(sax);
  }

  GridSourceSet parse(
      InputStream in,
      GmmSet gmmSet,
      ModelConfig config) throws SAXException, IOException {

    checkState(!used, "This parser has expired");
    this.gmmSet = gmmSet;
    this.config = config;
    sax.parse(in, this);
    used = true;
    return sourceSet;
  }

  @Override
  public void startElement(
      String uri,
      String localName,
      String qName,
      Attributes atts) throws SAXException {

    SourceElement e = null;
    try {
      e = SourceElement.fromString(qName);
    } catch (IllegalArgumentException iae) {
      throw new SAXParseException("Invalid element <" + qName + ">", locator, iae);
    }
    switch (e) {

      case GRID_SOURCE_SET:
        String name = readString(NAME, atts);
        int id = readInt(ID, atts);
        double weight = readDouble(WEIGHT, atts);
        sourceSetBuilder = new GridSourceSet.Builder();
        sourceSetBuilder
            .name(name)
            .id(id)
            .weight(weight);
        sourceSetBuilder.gmms(gmmSet);
        if (log.isLoggable(FINE)) {
          log.fine("");
          log.fine("       Name: " + name);
          log.fine("     Weight: " + weight);
        }
        mfdHelperBuilder = MfdHelper.builder();
        mfdHelper = mfdHelperBuilder.build(); // dummy; usually
        // overwritten
        break;

      case DEFAULT_MFDS:
        parsingDefaultMFDs = true;
        break;

      case INCREMENTAL_MFD:
        if (parsingDefaultMFDs) {
          mfdHelperBuilder.addDefault(atts);
        }
        break;

      case SOURCE_PROPERTIES:
        String depthMapStr = readString(MAG_DEPTH_MAP, atts);
        NavigableMap<Double, Map<Double, Double>> depthMap =
            stringToValueValueWeightMap(depthMapStr);
        double maxDepth = readDouble(MAX_DEPTH, atts);
        String mechMapStr = readString(FOCAL_MECH_MAP, atts);
        Map<FocalMech, Double> mechMap = stringToEnumWeightMap(mechMapStr, FocalMech.class);
        RuptureScaling rupScaling = readEnum(RUPTURE_SCALING, atts, RuptureScaling.class);
        sourceSetBuilder
            .depthMap(depthMap, type)
            .maxDepth(maxDepth, type)
            .mechs(mechMap)
            .ruptureScaling(rupScaling);
        double strike = readDouble(STRIKE, atts);
        // first validate strike by setting it in builder
        sourceSetBuilder.strike(strike);
        // then possibly override type if strike is set
        PointSourceType type = config.pointSourceType;
        if (!Double.isNaN(strike)) {
          type = PointSourceType.FIXED_STRIKE;
        }
        sourceSetBuilder.sourceType(type);
        if (log.isLoggable(FINE)) {
          log.fine("     Depths: " + depthMap);
          log.fine("  Max depth: " + maxDepth);
          log.fine("Focal mechs: " + mechMap);
          log.fine("Rup scaling: " + rupScaling);
          log.fine("     Strike: " + strike);
          String typeOverride = (type != config.pointSourceType) ? " (" +
              config.pointSourceType + " overridden)" : "";
          log.fine("Source type: " + type + typeOverride);
        }
        break;

      case NODES:
        String path = atts.getValue(PATH.toString());
        String ratefile = "inline";
        if (path != null) {
          externalRateFile = true;
          ratefile = Paths.get(RATE_DIR, path).toString();
          /*
           * TODO slab identifier needed; this relates to slab not reporting
           * correct source type
           */
          Path ratesPath = config.resource
              .resolveSibling(SourceType.GRID.toString())
              .resolve(RATE_DIR)
              .resolve(path);
          processRateCsv(ratesPath);
        }
        log.fine("      Rates: " + ratefile);
        break;

      case NODE:
        if (externalRateFile) {
          break;
        }
        readingLoc = true;
        locBuilder = new StringBuilder();
        setNodeMfd(atts);
        try {
          String nodeMechMapStr = readString(FOCAL_MECH_MAP, atts);
          nodeMechMap = stringToEnumWeightMap(nodeMechMapStr, FocalMech.class);
        } catch (NullPointerException npe) {
          nodeMechMap = null;
        }
        break;

      /*
       * TODO we need to check that delta mag, if included in a node, is
       * consistent with deltaMag of all default MFDs. Or, we check that all
       * defaults are consistent and don't permit inclusion of deltaMag as node
       * attribute. The same could be done for mMin. This ensures a basic
       * consistency of structure.
       */
    }
  }

  @Override
  public void endElement(
      String uri,
      String localName,
      String qName) throws SAXException {

    SourceElement e = null;
    try {
      e = SourceElement.fromString(qName);
    } catch (IllegalArgumentException iae) {
      throw new SAXParseException("Invalid element <" + qName + ">", locator, iae);
    }

    switch (e) {

      case DEFAULT_MFDS:
        parsingDefaultMFDs = false;
        mfdHelper = mfdHelperBuilder.build();
        break;

      case NODE:
        if (externalRateFile) {
          break;
        }
        readingLoc = false;
        Location loc = Location.fromString(locBuilder.toString());
        if (nodeMechMap != null) {
          sourceSetBuilder.location(loc, nodeMFD, nodeMechMap);
        } else {
          sourceSetBuilder.location(loc, nodeMFD);
        }
        nodeMFD = null;
        nodeMechMap = null;
        break;

      case GRID_SOURCE_SET:
        sourceSetBuilder.mfdData(minMag, maxMag, deltaMag);
        sourceSet = sourceSetBuilder.build();

        if (log.isLoggable(FINE)) {
          // TODO there must be a better way to organize this so that
          // we can log the depth model without having to give it package
          // visibility
          log.fine("       Size: " + sourceSet.size());
          log.finer("  MFD count: " + mfdHelper.size());
          log.finer("  Mag count: " + sourceSet.depthModel.magMaster.size());
          log.finer(" Mag master: " + sourceSet.depthModel.magMaster);
          log.finer("  MFD index: " + sourceSet.depthModel.magDepthIndices);
          log.finer("     Depths: " + sourceSet.depthModel.magDepthDepths);
          log.finer("    Weights: " + sourceSet.depthModel.magDepthWeights);
          log.fine("");
        }
        break;

    }
  }

  @Override
  public void characters(char ch[], int start, int length) throws SAXException {
    if (readingLoc) {
      locBuilder.append(ch, start, length);
    }
  }

  @Override
  public void setDocumentLocator(Locator locator) {
    this.locator = locator;
  }
  
  /* Build node MFD and set mag tracking values. */
  private void setNodeMfd(Attributes atts) {
    nodeMFD = processNode(atts);
    minMag = Math.min(minMag, nodeMFD.min().x());
    maxMag = Math.max(maxMag, nodeMFD.max().x());
  }

  private void processRateCsv(Path path) throws SAXException {
    try {
      
      if (!Files.exists(path)) {
        String mssg = String.format("Source file does not exist: %s", path);
        throw new FileNotFoundException(mssg);
      }
      List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);

      String header = lines.get(0);
      List<String> keys = Parsing.splitToList(header, Delimiter.COMMA);
      validateKeys(keys, path);

      int lineIndex = 0;
      for (String line : Iterables.skip(lines, 1)) {
        lineIndex++;

        /* Skip comments and empty lines. */
        if (line.startsWith("#")) {
          continue;
        }
        if (line.trim().isEmpty()) {
          continue;
        }

        List<String> values = Parsing.splitToList(line, Delimiter.COMMA);
        checkState(
            values.size() == keys.size(),
            "Incorrect number of values on line %s in %s",
            lineIndex,
            path.getFileName());

        AttributesImpl atts = new AttributesImpl();
        int keyIndex = 0;
        double lon = 0.0;
        double lat = 0.0;
        for (String key : keys) {
          String value = values.get(keyIndex);
          switch (key) {
            case LON_KEY:
              lon = Double.parseDouble(value);
              break;
            case LAT_KEY:
              lat = Double.parseDouble(value);
              break;
            default:
              atts.addAttribute("", key, key, "CDATA", value);
          }
          keyIndex++;
        }
        Location loc = Location.create(lat, lon);
        setNodeMfd(atts);
        sourceSetBuilder.location(loc, nodeMFD);

        /*
         * TODO add ability to support custom focal mechs in external file see
         * issue #261. This would require the following addition, from element
         * parser above:
         * 
         * if (nodeMechMap != null) { sourceSetBuilder.location(loc, nodeMFD,
         * nodeMechMap); }
         */
      }
    } catch (Exception e) {
      throw new SAXException(e);
    }
  }

  private static final String LON_KEY = "lon";
  private static final String LAT_KEY = "lat";

  private static final Set<String> NODE_KEYS = ImmutableSet.<String> builder()
      .addAll(Iterables.transform(
          EnumSet.range(TYPE, C_MAG),
          Functions.toStringFunction()))
      .add(LON_KEY, LAT_KEY)
      .build();

  private static void validateKeys(List<String> keys, Path path) {
    for (String key : keys) {
      if (!NODE_KEYS.contains(key)) {
        String mssg = String.format(
            "Grid source file [%s] contains invalid header key: %s",
            path.getFileName(),
            key);
        throw new IllegalStateException(mssg);
      }
    }
    validateKey(keys, LON_KEY, path);
    validateKey(keys, LAT_KEY, path);
  }

  private static void validateKey(List<String> keys, String key, Path path) {
    if (!keys.contains(key)) {
      String mssg = String.format(
          "Grid source file [%s] is missing key: %s",
          path.getFileName(),
          key);
      throw new IllegalStateException(mssg);
    }
  }

  /*
   * Currently, grid sources may have multiple defaults of a uniform type. No
   * checking is done to see if node types match defaults. Defaults are
   * collapsed into a single MFD.
   */

  private XySequence processNode(Attributes atts) {
    MfdType type = readEnum(TYPE, atts, MfdType.class);

    switch (type) {
      case GR:
        return buildCollapsedGR(atts);

      case INCR:
        return buildIncr(atts);

      case SINGLE:
        return buildCollapsedSingle(atts);

      case GR_TAPER:
        return buildTapered(atts);

      default:
        throw new IllegalStateException(type + " not yet implemented");
    }
  }

  /*
   * TODO The two methods, above and below, should be one in the same, however
   * INCR is not supported for external rate files
   */
  private XySequence processLine(Attributes atts) {
    MfdType type = readEnum(TYPE, atts, MfdType.class);

    switch (type) {
      case GR:
        return buildCollapsedGR(atts);

      case SINGLE:
        return buildCollapsedSingle(atts);

      case GR_TAPER:
        return buildTapered(atts);

      default:
        throw new IllegalStateException(type + " not yet implemented or supported");
    }
  }

  private XySequence buildGR(Attributes atts) {
    List<GR_Data> grDataList = mfdHelper.grData(atts);
    GR_Data grData = grDataList.get(0);
    deltaMag = grData.dMag;
    return Mfds.toSequence(buildGR(grData));
  }

  private XySequence buildCollapsedGR(Attributes atts) {
    List<GR_Data> dataList = mfdHelper.grData(atts);
    // validate callapsability
    GR_Data grModel = dataList.get(0);
    double mMin = grModel.mMin;
    double dMag = grModel.dMag;
    double mMax = grModel.mMax;
    for (GR_Data grData : Iterables.skip(dataList, 1)) {
      checkState(grData.mMin == mMin, "All mMin must be equal");
      checkState(grData.dMag == dMag, "All dMag must be equal");
      mMax = Math.max(grData.mMax, mMax);
    }

    deltaMag = dMag;

    double[] mags = Data.buildCleanSequence(mMin, mMax, dMag, true, 2);
    double[] rates = new double[mags.length];

    for (GR_Data grData : dataList) {
      IncrementalMfd mfd = buildGR(grData);
      List<Double> mfdRates = mfd.yValues();
      for (int i = 0; i < mfdRates.size(); i++) {
        rates[i] += mfdRates.get(i);
      }
    }

    return XySequence.createImmutable(mags, rates);
  }

  private static IncrementalMfd buildGR(GR_Data grData) {
    int nMagGR = Mfds.magCount(grData.mMin, grData.mMax, grData.dMag);
    IncrementalMfd mfdGR = Mfds.newGutenbergRichterMFD(grData.mMin, grData.dMag,
        nMagGR, grData.b, 1.0);
    mfdGR.scaleToIncrRate(grData.mMin, Mfds.incrRate(grData.a, grData.b, grData.mMin) *
        grData.weight);
    return mfdGR;
  }

  // TODO are there circumstances under which one would
  // combine multiple INCR MFDs??
  private XySequence buildIncr(Attributes atts) {
    List<IncrData> incrDataList = mfdHelper.incrementalData(atts);
    IncrData incrData = incrDataList.get(0);
    deltaMag = incrData.mags[1] - incrData.mags[0];
    return Mfds.toSequence(buildIncr(incrData));
  }

  private static IncrementalMfd buildIncr(IncrData incrData) {
    IncrementalMfd mfdIncr = Mfds.newIncrementalMFD(incrData.mags,
        Data.multiply(incrData.weight, incrData.rates));
    return mfdIncr;
  }

  private XySequence buildSingle(Attributes atts) {
    List<SingleData> singleDataList = mfdHelper.singleData(atts);
    SingleData singleData = singleDataList.get(0);
    deltaMag = Double.NaN;
    return Mfds.toSequence(buildSingle(singleData));
  }

  private XySequence buildCollapsedSingle(Attributes atts) {
    List<SingleData> dataList = mfdHelper.singleData(atts);
    deltaMag = Double.NaN;

    double[] mags = new double[dataList.size()];
    double[] rates = new double[mags.length];

    for (int i = 0; i < dataList.size(); i++) {
      SingleData data = dataList.get(i);
      mags[i] = data.m;
      rates[i] = data.rate * data.weight;
    }

    return XySequence.createImmutable(mags, rates);
  }

  private static IncrementalMfd buildSingle(SingleData singleData) {
    return Mfds.newSingleMFD(singleData.m, singleData.rate * singleData.weight,
        singleData.floats);
  }

  private XySequence buildTapered(Attributes atts) {
    List<TaperData> taperDataList = mfdHelper.taperData(atts);
    TaperData taperData = taperDataList.get(0);
    deltaMag = taperData.dMag;
    return Mfds.toSequence(buildTapered(taperData));
  }

  private static IncrementalMfd buildTapered(TaperData taperData) {
    int nMagTaper = Mfds.magCount(taperData.mMin, taperData.mMax, taperData.dMag);
    IncrementalMfd mfdTaper = Mfds.newTaperedGutenbergRichterMFD(taperData.mMin,
        taperData.dMag, nMagTaper, taperData.a, taperData.b, taperData.cMag,
        taperData.weight);
    return mfdTaper;
  }

}
