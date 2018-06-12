package gov.usgs.earthquake.nshmp.calc;

import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.logging.Logger;

import com.google.common.base.Stopwatch;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Doubles;

import gov.usgs.earthquake.nshmp.data.XySequence;
import gov.usgs.earthquake.nshmp.eq.model.SourceType;
import gov.usgs.earthquake.nshmp.geo.Location;
import gov.usgs.earthquake.nshmp.internal.Parsing;
import gov.usgs.earthquake.nshmp.internal.Parsing.Delimiter;

/**
 * Earthquake rate and probability exporter.
 *
 * @author Peter Powers
 */
public final class EqRateExport {

  private static final String RATE_FORMAT = "%.8g";
  private static final String PROB_FORMAT = "%.2f";
  private static final String RATE_FILE = "rates.csv";
  private static final String PROB_FILE = "probs.csv";

  private final Logger log;
  private final Path dir;
  private final String file;
  private final String valueFormat;
  private final CalcConfig config;
  private final boolean exportSource;

  private final Stopwatch batchWatch;
  private final Stopwatch totalWatch;
  private int batchCount = 0;
  private int resultCount = 0;

  private final boolean namedSites;
  private boolean firstBatch = true;
  private boolean used = false;

  private final List<EqRate> rates;

  private EqRateExport(CalcConfig config, Sites sites, Logger log) throws IOException {

    // whether or not rates or probabilities have been calculated
    boolean rates = config.rate.valueFormat == ValueFormat.ANNUAL_RATE;

    this.log = log;
    this.dir = HazardExport.createOutputDir(config.output.directory);
    this.file = rates ? RATE_FILE : PROB_FILE;
    this.valueFormat = rates ? RATE_FORMAT : PROB_FORMAT;
    this.config = config;
    this.exportSource = config.output.dataTypes.contains(DataType.SOURCE);
    this.rates = new ArrayList<>();

    Site demoSite = sites.iterator().next();
    this.namedSites = demoSite.name() != Site.NO_NAME;

    this.batchWatch = Stopwatch.createStarted();
    this.totalWatch = Stopwatch.createStarted();
  }

  /**
   * Create a new results handler.
   * 
   * @param config that specifies output options and formats
   * @param sites reference to the sites to be processed (not retained)
   * @param log shared logging instance from calling class
   * @throws IllegalStateException if binary output has been specified in the
   *         {@code config} but the {@code sites} container does not specify map
   *         extents.
   */
  public static EqRateExport create(
      CalcConfig config,
      Sites sites,
      Logger log) throws IOException {

    return new EqRateExport(config, sites, log);
  }

  /**
   * Add the supplied {@code EqRate}s to this exporter.
   * 
   * @param rates data containers to add
   */
  public void addAll(Collection<EqRate> rates) throws IOException {
    for (EqRate rate : rates) {
      add(rate);
    }
  }

  /**
   * Add an {@code EqRate} to this exporter.
   * 
   * @param rate data container to add
   */
  public void add(EqRate rate) throws IOException {
    checkState(!used, "This result handler is expired");
    resultCount++;
    rates.add(rate);
    if (rates.size() == config.output.flushLimit) {
      flush();
      batchCount++;
      log.info(String.format(
          "     batch: %s in %s – %s sites in %s",
          batchCount, batchWatch, resultCount, totalWatch));
      batchWatch.reset().start();
    }
  }

  /**
   * Flushes any remaining results, stops all timers and sets the state of this
   * exporter to 'used'; no more results may be added.
   */
  public void expire() throws IOException {
    flush();
    batchWatch.stop();
    totalWatch.stop();
    used = true;
  }

  /*
   * Flush any stored Hazard and Deaggregation results to file, clearing
   */
  private void flush() throws IOException {
    if (!rates.isEmpty()) {
      writeRates();
      rates.clear();
      firstBatch = false;
    }
  }

  /**
   * The number of hazard [and deagg] results passed to this handler thus far.
   */
  public int resultsProcessed() {
    return resultCount;
  }

  /**
   * The number of {@code Hazard} results this handler is currently storing.
   */
  public int size() {
    return rates.size();
  }

  /**
   * A string representation of the time duration that this result handler has
   * been running.
   */
  public String elapsedTime() {
    return totalWatch.toString();
  }

  /**
   * The target output directory established by this handler.
   */
  public Path outputDir() {
    return dir;
  }

  /*
   * Write the current list of {@code EqRate}s to file.
   */
  private void writeRates() throws IOException {

    EqRate demo = rates.get(0);

    Iterable<Double> emptyValues = Doubles.asList(new double[demo.totalMfd.size()]);

    OpenOption[] options = firstBatch ? HazardExport.WRITE : HazardExport.APPEND;

    Function<Double, String> formatter = Parsing.formatDoubleFunction(valueFormat);

    /* Line maps for ascii output; may or may not be used */
    List<String> totalLines = new ArrayList<>();
    Map<SourceType, List<String>> typeLines = Maps.newEnumMap(SourceType.class);

    if (firstBatch) {
      Iterable<?> header = Iterables.concat(
          Lists.newArrayList(namedSites ? "name" : null, "lon", "lat"),
          demo.totalMfd.xValues());
      totalLines.add(Parsing.join(header, Delimiter.COMMA));
    }

    if (exportSource) {
      // TODO get source types from model
      for (SourceType type : SourceType.values()) {
        typeLines.put(type, Lists.newArrayList(totalLines));
      }
    }

    /* Process batch */
    for (EqRate rate : rates) {

      String name = namedSites ? rate.site.name : null;
      Location location = rate.site.location;

      List<String> locData = Lists.newArrayList(
          name,
          String.format("%.5f", location.lon()),
          String.format("%.5f", location.lat()));

      String line = toLine(locData, rate.totalMfd.yValues(), formatter);
      totalLines.add(line);

      String emptyLine = toLine(locData, emptyValues, formatter);

      if (exportSource) {
        for (Entry<SourceType, List<String>> entry : typeLines.entrySet()) {
          SourceType type = entry.getKey();
          String typeLine = emptyLine;
          if (rate.typeMfds.containsKey(type)) {
            XySequence typeRate = rate.typeMfds.get(type);
            typeLine = toLine(locData, typeRate.yValues(), formatter);
          }
          entry.getValue().add(typeLine);
        }
      }

      /* write/append */
      Path totalFile = dir.resolve(file);
      Files.write(totalFile, totalLines, UTF_8, options);
      if (exportSource) {
        Path parentDir = dir.resolve(HazardExport.TYPE_DIR);
        for (Entry<SourceType, List<String>> typeEntry : typeLines.entrySet()) {
          SourceType type = typeEntry.getKey();
          Path typeDir = parentDir.resolve(type.name());
          Files.createDirectories(typeDir);
          Path typeFile = typeDir.resolve(file);
          Files.write(typeFile, typeEntry.getValue(), UTF_8, options);
        }
      }
    }
  }

  private static String toLine(
      Iterable<String> location,
      Iterable<Double> values,
      Function<Double, String> formatter) {

    return Parsing.join(
        FluentIterable.from(location)
            .append(Iterables.transform(
                values,
                formatter::apply)),
        Delimiter.COMMA);
  }

}
