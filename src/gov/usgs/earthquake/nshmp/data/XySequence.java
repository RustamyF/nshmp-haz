package gov.usgs.earthquake.nshmp.data;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static gov.usgs.earthquake.nshmp.data.Data.areMonotonic;
import static gov.usgs.earthquake.nshmp.internal.TextUtils.NEWLINE;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.function.Function;

import com.google.common.base.Joiner;
import com.google.common.primitives.Doubles;

/**
 * Sequence of xy-value pairs that is iterable ascending in x. Once created, the
 * x-values of a sequence are immutable. This class provides static operations
 * to create instances of sequences that have both mutable and immutable
 * y-values. All data supplied to these operations is defensively copied unless
 * it is not necessary to do so. For instance, {@code *copyOf()} variants should
 * be used where possible as x-values will never be replicated in memory.
 *
 * <p>Although this class is not final, it can not be subclassed. Mutable
 * instances of this class are not thread-safe for operations that alter
 * y-values.
 *
 * @author Peter Powers
 */
public abstract class XySequence implements Iterable<XyPoint> {

  /**
   * Create a new sequence with mutable y-values from the supplied value arrays.
   * If the supplied y-value array is null, all y-values are initialized to 0.
   *
   * @param xs x-values to initialize sequence with
   * @param ys y-values to initialize sequence with; may be null
   * @return a mutable, {@code double[]}-backed sequence
   * @throws NullPointerException if {@code xs} are null
   * @throws IllegalArgumentException if {@code xs} and {@code ys} are not the
   *         same size
   * @throws IllegalArgumentException if {@code xs} does not increase
   *         monotonically or contains repeated values
   */
  public static XySequence create(double[] xs, double[] ys) {
    return create(xs, ys, true);
  }

  /**
   * Create a new, immutable sequence from the supplied value arrays. Unlike
   * {@link #create(double[], double[])}, the supplied y-value array may not be
   * null.
   *
   * @param xs x-values to initialize sequence with
   * @param ys y-values to initialize sequence with
   * @return an immutable, {@code double[]}-backed sequence
   * @throws NullPointerException if {@code xs} or {@code ys} are null
   * @throws IllegalArgumentException if {@code xs} and {@code ys} are not the
   *         same size
   * @throws IllegalArgumentException if {@code xs} does not increase
   *         monotonically or contains repeated values
   */
  public static XySequence createImmutable(double[] xs, double[] ys) {
    return create(xs, checkNotNull(ys), false);
  }

  private static XySequence create(double[] xs, double[] ys, boolean mutable) {
    return construct(
        Arrays.copyOf(xs, xs.length),
        (ys == null) ? new double[xs.length] : Arrays.copyOf(ys, ys.length),
        mutable);
  }

  /**
   * Create a new sequence with mutable y-values from the supplied value
   * collections. If the y-value collection is null, all y-values are
   * initialized to 0.
   *
   * @param xs x-values to initialize sequence with
   * @param ys y-values to initialize sequence with; may be null
   * @return a mutable, {@code double[]}-backed sequence
   * @throws NullPointerException if {@code xs} are null
   * @throws IllegalArgumentException if {@code xs} and {@code ys} are not the
   *         same size
   * @throws IllegalArgumentException if {@code xs} does not increase
   *         monotonically or contains repeated values
   */
  public static XySequence create(
      Collection<? extends Number> xs,
      Collection<? extends Number> ys) {
    return create(xs, ys, true);
  }

  /**
   * Create a new, immutable sequence from the supplied value collections.
   * Unlike {@link #create(Collection, Collection)} , the supplied y-value
   * collection may not be null.
   *
   * @param xs x-values to initialize sequence with
   * @param ys y-values to initialize sequence with
   * @return an immutable, {@code double[]}-backed sequence
   * @throws NullPointerException if {@code xs} or {@code ys} are null
   * @throws IllegalArgumentException if {@code xs} and {@code ys} are not the
   *         same size
   * @throws IllegalArgumentException if {@code xs} does not increase
   *         monotonically or contains repeated values
   */
  public static XySequence createImmutable(
      Collection<? extends Number> xs,
      Collection<? extends Number> ys) {
    return create(xs, checkNotNull(ys), false);
  }

  private static XySequence create(
      Collection<? extends Number> xs,
      Collection<? extends Number> ys,
      boolean mutable) {

    return construct(
        Doubles.toArray(xs),
        (ys == null) ? new double[xs.size()] : Doubles.toArray(ys),
        mutable);
  }

  private static XySequence construct(double[] xs, double[] ys, boolean mutable) {
    checkArgument(xs.length > 0, "x-values may not be empty");
    checkArgument(xs.length == ys.length, "x- and y-values are different sizes");
    if (xs.length > 1) {
      checkArgument(areMonotonic(true, true, xs), "x-values do not increase monotonically");
    }
    return mutable ? new MutableXySequence(xs, ys) : new ImmutableXySequence(xs, ys);
  }

  /**
   * Create a mutable copy of the supplied {@code sequence}.
   *
   * @param sequence to copy
   * @return a mutable copy of the supplied {@code sequence}
   * @throws NullPointerException if the supplied {@code sequence} is null
   */
  public static XySequence copyOf(XySequence sequence) {
    return new MutableXySequence(checkNotNull(sequence), false);
  }

  /**
   * Create a mutable copy of the supplied {@code sequence} with all y-values
   * reset to zero.
   *
   * @param sequence to copy
   * @return a mutable copy of the supplied {@code sequence}
   * @throws NullPointerException if the supplied {@code sequence} is null
   */
  public static XySequence emptyCopyOf(XySequence sequence) {
    return new MutableXySequence(checkNotNull(sequence), true);
  }

  /**
   * Create an immutable copy of the supplied {@code sequence}.
   *
   * @param sequence to copy
   * @return an immutable copy of the supplied {@code sequence}
   * @throws NullPointerException if the supplied {@code sequence} is null
   */
  public static XySequence immutableCopyOf(XySequence sequence) {
    return (sequence.getClass().equals(ImmutableXySequence.class)) ? sequence
        : new ImmutableXySequence(sequence, false);
  }

  /**
   * Create a resampled version of the supplied {@code sequence}. Method
   * resamples via linear interpolation and does not extrapolate beyond the
   * domain of the source {@code sequence}; y-values with x-values outside the
   * domain of the source sequence are set to 0.
   *
   * @param sequence to resample
   * @param xs resample values
   * @return a resampled sequence
   */
  @Deprecated
  public static XySequence resampleTo(XySequence sequence, double[] xs) {
    // NOTE TODO this will support mfd combining
    checkNotNull(sequence);
    checkArgument(checkNotNull(xs).length > 0);
    double[] yResample = Interpolate.findY(sequence.xValues(), sequence.yValues(), xs);
    // TODO disable extrapolation
    if (true) {
      throw new UnsupportedOperationException();
    }
    return XySequence.create(xs, yResample);
  }

  XySequence() {}

  /**
   * Returns the x-value at {@code index}.
   * @param index to retrieve
   * @return the x-value at {@code index}
   * @throws IndexOutOfBoundsException if the index is out of range (
   *         {@code index < 0 || index >= size()})
   */
  public abstract double x(int index);

  abstract double xUnchecked(int index);

  /**
   * Returns the y-value at {@code index}.
   * @param index to retrieve
   * @return the y-value at {@code index}
   * @throws IndexOutOfBoundsException if the index is out of range (
   *         {@code index < 0 || index >= size()})
   */
  public abstract double y(int index);

  abstract double yUnchecked(int index);

  /**
   * Sets the y-{@code value} at {@code index}.
   * @param index of y-{@code value} to set.
   * @param value to set
   * @throws IndexOutOfBoundsException if the index is out of range (
   *         {@code index < 0 || index >= size()})
   */
  public void set(int index, double value) {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the number or points in this sequence.
   * @return the sequence size
   */
  public abstract int size();

  /**
   * Returns an immutable {@code List} of the sequence x-values.
   * @return the {@code List} of x-values
   */
  public List<Double> xValues() {
    return new X_List();
  }

  private final class X_List extends AbstractList<Double> implements RandomAccess {

    @Override
    public Double get(int index) {
      return x(index);
    }

    @Override
    public int size() {
      return XySequence.this.size();
    }

    @Override
    public Iterator<Double> iterator() {
      return new Iterator<Double>() {
        private final int size = size();
        private int caret = 0;

        @Override
        public boolean hasNext() {
          return caret < size;
        }

        @Override
        public Double next() {
          return xUnchecked(caret++);
        }

        @Override
        public void remove() {
          throw new UnsupportedOperationException();
        }
      };
    }
  }

  /**
   * Returns an immutable {@code List} of the sequence y-values.
   * @return the {@code List} of y-values
   */
  public List<Double> yValues() {
    return new Y_List();
  }

  private final class Y_List extends AbstractList<Double> implements RandomAccess {

    @Override
    public Double get(int index) {
      return y(index);
    }

    @Override
    public int size() {
      return XySequence.this.size();
    }

    @Override
    public Iterator<Double> iterator() {
      return new Iterator<Double>() {
        private int caret = 0;
        private final int size = size();

        @Override
        public boolean hasNext() {
          return caret < size;
        }

        @Override
        public Double next() {
          return yUnchecked(caret++);
        }

        @Override
        public void remove() {
          throw new UnsupportedOperationException();
        }
      };
    }
  }

  /**
   * Returns an iterator over the {@link XyPoint}s in this sequence. For
   * immutable implementations, the {@link XyPoint#set(double)} method of a
   * returned point throws an {@code UnsupportedOperationException}.
   */
  @Override
  public Iterator<XyPoint> iterator() {
    return new XyIterator(false);
  }

  class XyIterator implements Iterator<XyPoint> {
    private final boolean mutable;
    private final int size = size();
    private int caret = 0;

    XyIterator(boolean mutable) {
      this.mutable = mutable;
    }

    @Override
    public boolean hasNext() {
      return caret < size;
    }

    @Override
    public XyPoint next() {
      return new Point(caret++, mutable);
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * The first {@link XyPoint} in this sequence.
   */
  public XyPoint min() {
    return new Point(0, false);
  }

  /**
   * The last {@link XyPoint} in this sequence.
   */
  public XyPoint max() {
    return new Point(size() - 1, false);
  }

  class Point implements XyPoint {

    final int index;
    final boolean mutable;

    Point(int index, boolean mutable) {
      this.index = index;
      this.mutable = mutable;
    }

    @Override
    public double x() {
      return XySequence.this.xUnchecked(index);
    }

    @Override
    public double y() {
      return XySequence.this.yUnchecked(index);
    }

    @Override
    public void set(double y) {
      if (mutable) {
        XySequence.this.set(index, y);
        return;
      }
      throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
      return "XyPoint: [" + x() + ", " + y() + "]";
    }

  }

  /**
   * Returns a readable string representation of this sequence.
   */
  @Override
  public String toString() {
    return new StringBuilder(getClass().getSimpleName())
        .append(":")
        .append(NEWLINE)
        .append(Joiner.on(NEWLINE).join(this))
        .toString();
  }

  /**
   * Add a {@code term} to the y-values of this sequence in place.
   *
   * @param term to add
   * @return {@code this} sequence, for use inline
   */
  public XySequence add(double term) {
    throw new UnsupportedOperationException();
  }

  /**
   * Add the supplied y-values to the y-values of this sequence in place.
   *
   * @param ys y-values to add
   * @return {@code this} sequence, for use inline
   */
  public XySequence add(double[] ys) {
    throw new UnsupportedOperationException();
  }

  /**
   * Add the y-values of a sequence to the y-values of this sequence in place.
   *
   * @param sequence to add
   * @return {@code this} sequence, for use inline
   * @throws IllegalArgumentException if
   *         {@code sequence.xValues() != this.xValues()}
   */
  public XySequence add(XySequence sequence) {
    throw new UnsupportedOperationException();
  }

  /**
   * Multiply ({@code scale}) the y-values of this sequence in place.
   *
   * @param scale factor
   * @return {@code this} sequence, for use inline
   */
  public XySequence multiply(double scale) {
    throw new UnsupportedOperationException();
  }

  /**
   * Multiply the y-values of this sequence by the y-values of another sequence
   * in place.
   *
   * @param sequence to multiply {@code this} sequence by
   * @return {@code this} sequence, for use inline
   * @throws IllegalArgumentException if
   *         {@code sequence.xValues() != this.xValues()}
   */
  public XySequence multiply(XySequence sequence) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the y-values of this sequence to their complement in place [
   * {@code 1 - y}]. Assumes this is a probability function limited to the
   * domain [0 1].
   *
   * @return {@code this} sequence, for use inline
   */
  public XySequence complement() {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets all y-values to 0.
   *
   * @return {@code this} sequence, for use inline
   */
  public XySequence clear() {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns {@code true} if all y-values are 0.0; {@code false} otherwise. a
   */
  public abstract boolean isClear();

  /**
   * Returns a new, immutable sequence that has had all leading and trailing
   * zero-valued points ({@code y = 0}) removed. Any zero-valued points in the
   * middle of this sequence are ignored.
   * 
   * @throws IllegalStateException if {@link #isClear() this.isClear()} as empty
   *         sequences are not permitted
   */
  public abstract XySequence trim();

  /**
   * Transforms all y-values in place using the supplied {@link Function}.
   *
   * @param function for transform
   * @return {@code this} sequence, for use inline
   */
  public XySequence transform(Function<Double, Double> function) {
    throw new UnsupportedOperationException();
  }

  /**
   * Adds {@code this} sequence to any exisiting sequence for {@code key} in the
   * supplied {@code map}. If {@code key} does not exist in the {@code map},
   * method puts a mutable copy of {@code this} in the map.
   * 
   * @param key for sequence to add
   * @param map of sequences to add to
   * @throws IllegalArgumentException if the x-values of added sequences to not
   *         match those of existing sequences
   */
  public final <E extends Enum<E>> void addToMap(E key, Map<E, XySequence> map) {
    if (map.containsKey(key)) {
      map.get(key).add(this);
    } else {
      map.put(key, XySequence.copyOf(this));
    }
  }

}
