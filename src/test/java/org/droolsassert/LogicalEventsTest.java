package org.droolsassert;

import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

@DroolsSession("classpath:/org/droolsassert/logicalEvents.drl")
public class LogicalEventsTest {

	@Rule
	public DroolsAssert drools = new DroolsAssert();

	@Before
	public void before() {
		drools.setGlobal("stdout", System.out);
	}

	@Test
	@AssertRules({
			"input call",
			"drop dial-up if callee is talking",
			"drop the call if caller is talking more than permitted time",
			"call in progress dropped",
			"input call dropped"
	})
	public void testCallsConnectAndDisconnectLogic() {
		Dialing caller1Dial = new Dialing("11111", "22222");
		drools.insertAndFire(caller1Dial);
		drools.assertRetracted(caller1Dial);
		CallInProgress call = drools.getObject(CallInProgress.class);
		assertEquals("11111", call.callerNumber);

		drools.advanceTime(5, MINUTES);
		Dialing caller3Dial = new Dialing("33333", "22222");
		drools.insertAndFire(caller3Dial);
		drools.assertExists(caller3Dial);

		drools.advanceTime(5, SECONDS);
		drools.assertExists(call);
		drools.assertExists(caller3Dial);

		drools.advanceTime(5, SECONDS);
		drools.assertExists(call);
		drools.assertRetracted(caller3Dial);

		drools.advanceTime(1, HOURS);
		drools.assertRetracted(call);
		drools.assertRetracted(caller3Dial);

		drools.assertAllRetracted();
	}

	public static class Dialing {
		public String callerNumber;
		public String calleeNumber;

		public Dialing(String callerNumber, String calleeNumber) {
			this.callerNumber = callerNumber;
			this.calleeNumber = calleeNumber;
		}
	}

	public static class CallInProgress {
		public String callerNumber;
		public String calleeNumber;

		public CallInProgress(String callerNumber, String calleeNumber) {
			this.callerNumber = callerNumber;
			this.calleeNumber = calleeNumber;
		}
	}

	public static class CallDropped {
		public String number;
		public String reason;

		public CallDropped(String number, String reason) {
			this.number = number;
			this.reason = reason;
		}
	}
}
