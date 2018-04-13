package gov.usgs.earthquake.nshmp.eq.fault.surface;

import org.junit.BeforeClass;
import org.junit.Test;

import gov.usgs.earthquake.nshmp.eq.fault.surface.DefaultGriddedSurface;
import gov.usgs.earthquake.nshmp.geo.Location;
import gov.usgs.earthquake.nshmp.geo.LocationList;

@SuppressWarnings("javadoc")
public class RuptureFloatingTest {

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {}

  // @Test public final void testCreateFloatingRuptures() {
  // fail("Not yet implemented"); // TODO
  // }
  //
  @Test
  public final void testOff() {

    LocationList trace = LocationList.create(
        Location.create(34.0, -118.0),
        Location.create(34.4, -118.0));

    // DefaultGriddedSurface surface = DefaultGriddedSurface.builder()
    // .trace(trace)
    // .dip(90)
    // .width(15.0)
    // .build();
    //
    // fail("Not yet implemented"); // TODO
  }

  public static void main(String[] args) {

    LocationList trace = LocationList.create(
        Location.create(34.0, -118.0),
        Location.create(34.4, -118.0));

    DefaultGriddedSurface surface = DefaultGriddedSurface.builder()
        .trace(trace)
        .depth(0.0)
        .dip(90.0)
        .width(15.0)
        .build();

    System.out.println(surface);
    System.out.println(surface.dipSpacing);
    System.out.println(surface.strikeSpacing);
    System.out.println(surface.getNumRows());
    System.out.println(surface.getNumCols());

  }
}
