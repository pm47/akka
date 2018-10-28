/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.typed.tutorial_5;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static jdocs.typed.tutorial_5.DeviceManagerProtocol.*;
import static jdocs.typed.tutorial_5.DeviceProtocol.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class DeviceGroupTest extends JUnitSuite {

  @ClassRule
  public static final TestKitJunitResource testKit = new TestKitJunitResource();

  @Test
  public void testReplyToRegistrationRequests() {
    TestProbe<DeviceRegistered> probe = testKit.createTestProbe(DeviceRegistered.class);
    ActorRef<DeviceGroupMessage> groupActor = testKit.spawn(DeviceGroup.behavior("group"));

    groupActor.tell(new RequestTrackDevice("group", "device", probe.getRef()));
    DeviceRegistered registered1 = probe.expectMessageClass(DeviceRegistered.class);

    // another deviceId
    groupActor.tell(new RequestTrackDevice("group", "device3", probe.getRef()));
    DeviceRegistered registered2 = probe.expectMessageClass(DeviceRegistered.class);
    assertNotEquals(registered1.device, registered2.device);

    // Check that the device actors are working
    TestProbe<TemperatureRecorded> recordProbe = testKit.createTestProbe(TemperatureRecorded.class);
    registered1.device.tell(new RecordTemperature(0L, 1.0, recordProbe.getRef()));
    assertEquals(0L, recordProbe.expectMessageClass(TemperatureRecorded.class).requestId);
    registered2.device.tell(new RecordTemperature(1L, 2.0, recordProbe.getRef()));
    assertEquals(1L, recordProbe.expectMessageClass(TemperatureRecorded.class).requestId);
  }

  @Test
  public void testIgnoreWrongRegistrationRequests() {
    TestProbe<DeviceRegistered> probe = testKit.createTestProbe(DeviceRegistered.class);
    ActorRef<DeviceGroupMessage> groupActor = testKit.spawn(DeviceGroup.behavior("group"));
    groupActor.tell(new RequestTrackDevice("wrongGroup", "device1", probe.getRef()));
    probe.expectNoMessage();
  }

  @Test
  public void testReturnSameActorForSameDeviceId() {
    TestProbe<DeviceRegistered> probe = testKit.createTestProbe(DeviceRegistered.class);
    ActorRef<DeviceGroupMessage> groupActor = testKit.spawn(DeviceGroup.behavior("group"));

    groupActor.tell(new RequestTrackDevice("group", "device", probe.getRef()));
    DeviceRegistered registered1 = probe.expectMessageClass(DeviceRegistered.class);

    // registering same again should be idempotent
    groupActor.tell(new RequestTrackDevice("group", "device", probe.getRef()));
    DeviceRegistered registered2 = probe.expectMessageClass(DeviceRegistered.class);
    assertEquals(registered1.device, registered2.device);
  }

  @Test
  public void testListActiveDevices() {
    TestProbe<DeviceRegistered> registeredProbe = testKit.createTestProbe(DeviceRegistered.class);
    ActorRef<DeviceGroupMessage> groupActor = testKit.spawn(DeviceGroup.behavior("group"));

    groupActor.tell(new RequestTrackDevice("group", "device1", registeredProbe.getRef()));
    registeredProbe.expectMessageClass(DeviceRegistered.class);

    groupActor.tell(new RequestTrackDevice("group", "device2", registeredProbe.getRef()));
    registeredProbe.expectMessageClass(DeviceRegistered.class);

    TestProbe<ReplyDeviceList> deviceListProbe = testKit.createTestProbe(ReplyDeviceList.class);

    groupActor.tell(new RequestDeviceList(0L, "group", deviceListProbe.getRef()));
    ReplyDeviceList reply = deviceListProbe.expectMessageClass(ReplyDeviceList.class);
    assertEquals(0L, reply.requestId);
    assertEquals(Stream.of("device1", "device2").collect(Collectors.toSet()), reply.ids);
  }

  @Test
  public void testListActiveDevicesAfterOneShutsDown() {
    TestProbe<DeviceRegistered> registeredProbe = testKit.createTestProbe(DeviceRegistered.class);
    ActorRef<DeviceGroupMessage> groupActor = testKit.spawn(DeviceGroup.behavior("group"));

    groupActor.tell(new RequestTrackDevice("group", "device1", registeredProbe.getRef()));
    DeviceRegistered registered1 = registeredProbe.expectMessageClass(DeviceRegistered.class);

    groupActor.tell(new RequestTrackDevice("group", "device2", registeredProbe.getRef()));
    registeredProbe.expectMessageClass(DeviceRegistered.class);

    ActorRef<DeviceMessage> toShutDown = registered1.device;

    TestProbe<ReplyDeviceList> deviceListProbe = testKit.createTestProbe(ReplyDeviceList.class);

    groupActor.tell(new RequestDeviceList(0L, "group", deviceListProbe.getRef()));
    ReplyDeviceList reply = deviceListProbe.expectMessageClass(ReplyDeviceList.class);
    assertEquals(0L, reply.requestId);
    assertEquals(Stream.of("device1", "device2").collect(Collectors.toSet()), reply.ids);

    toShutDown.tell(Passivate.INSTANCE);
    registeredProbe.expectTerminated(toShutDown, registeredProbe.getRemainingOrDefault());

    // using awaitAssert to retry because it might take longer for the groupActor
    // to see the Terminated, that order is undefined
    registeredProbe.awaitAssert(() -> {
      groupActor.tell(new RequestDeviceList(1L, "group", deviceListProbe.getRef()));
      ReplyDeviceList r =
        deviceListProbe.expectMessageClass(ReplyDeviceList.class);
      assertEquals(1L, r.requestId);
      assertEquals(Stream.of("device2").collect(Collectors.toSet()), r.ids);
      return null;
    });
  }

    //#group-query-integration-test
  @Test
  public void testCollectTemperaturesFromAllActiveDevices() {
    TestProbe<DeviceRegistered> registeredProbe = testKit.createTestProbe(DeviceRegistered.class);
    ActorRef<DeviceGroupMessage> groupActor = testKit.spawn(DeviceGroup.behavior("group"));

    groupActor.tell(new RequestTrackDevice("group", "device1", registeredProbe.getRef()));
    ActorRef<DeviceMessage> deviceActor1 = registeredProbe.expectMessageClass(DeviceRegistered.class).device;

    groupActor.tell(new RequestTrackDevice("group", "device2", registeredProbe.getRef()));
    ActorRef<DeviceMessage> deviceActor2 = registeredProbe.expectMessageClass(DeviceRegistered.class).device;

    groupActor.tell(new RequestTrackDevice("group", "device3", registeredProbe.getRef()));
    ActorRef<DeviceMessage> deviceActor3 = registeredProbe.expectMessageClass(DeviceRegistered.class).device;

    // Check that the device actors are working
    TestProbe<TemperatureRecorded> recordProbe = testKit.createTestProbe(TemperatureRecorded.class);
    deviceActor1.tell(new RecordTemperature(0L, 1.0, recordProbe.getRef()));
    assertEquals(0L, recordProbe.expectMessageClass(TemperatureRecorded.class).requestId);
    deviceActor2.tell(new RecordTemperature(1L, 2.0, recordProbe.getRef()));
    assertEquals(1L, recordProbe.expectMessageClass(TemperatureRecorded.class).requestId);
    // No temperature for device 3

    TestProbe<RespondAllTemperatures> allTempProbe = testKit.createTestProbe(RespondAllTemperatures.class);
    groupActor.tell(new RequestAllTemperatures(0L, "group", allTempProbe.getRef()));
    RespondAllTemperatures response = allTempProbe.expectMessageClass(RespondAllTemperatures.class);
    assertEquals(0L, response.requestId);

    Map<String, TemperatureReading> expectedTemperatures = new HashMap<>();
    expectedTemperatures.put("device1", new Temperature(1.0));
    expectedTemperatures.put("device2", new Temperature(2.0));
    expectedTemperatures.put("device3", TemperatureNotAvailable.INSTANCE);

    assertEquals(expectedTemperatures, response.temperatures);
  }
  //#group-query-integration-test
}
