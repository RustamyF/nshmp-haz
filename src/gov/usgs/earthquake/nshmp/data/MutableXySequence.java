package gov.usgs.earthquake.nshmp.data;

import static com.google.common.base.Preconditions.checkElementIndex;

import java.util.Arrays;
import java.util.Iterator;
import java.util.function.Function;

/**
 * Mutable variant of {@code XySequence}.
 *
 * @author Peter Powers
 */
final class MutableXySequence extends ImmutableXySequence {

  MutableXySequence(double[] xs, double[] ys) {
    super(xs, ys);
  }

  MutableXySequence(XySequence sequence, boolean clear) {
    super(sequence, clear);
  }

  @Override
  public Iterator<XyPoint> iterator() {
    return new XyIterator(true);
  }

  @Override
  public XyPoint min() {
    return new Point(0, true);
  }

  @Override
  public XyPoint max() {
    return new Point(size() - 1, true);
  }

  @Override
  public void set(int index, double value) {
    checkElementIndex(index, xs.length);
    ys[index] = value;
  }

  @Override
  public XySequence add(double term) {
    Data.add(term, ys);
    return this;
  }

  @Override
  public XySequence add(double[] ys) {
    Data.add(this.ys, ys);
    return this;
  }

  @Override
  public XySequence add(XySequence sequence) {
    // safe covariant cast
    Data.uncheckedAdd(ys, validateSequence((ImmutableXySequence) sequence).ys);
    return this;
  }

  @Override
  public XySequence multiply(double scale) {
    Data.multiply(scale, ys);
    return this;
  }

  @Override
  public XySequence multiply(XySequence sequence) {
    // safe covariant cast
    Data.uncheckedMultiply(ys, validateSequence((ImmutableXySequence) sequence).ys);
    return this;
  }

  @Override
  public XySequence complement() {
    Data.add(1, Data.flip(ys));
    return this;
  }

  @Override
  public XySequence clear() {
    Arrays.fill(ys, 0.0);
    return this;
  }

  @Override
  public XySequence transform(Function<Double, Double> function) {
    Data.transform(function, ys);
    return this;
  }

}
