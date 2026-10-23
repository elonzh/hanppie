package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.robot.display.*
import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import cn.elonzh.hanppie.ui.robot.display.*
import kotlinx.coroutines.*
import kotlin.test.*

class RobotDisplayConnectionTest {
    @Test fun readsPreserveInputButExplicitWritesUseRootTaskAdmissionAndStopManualInput(): Unit = runBlocking {
        val peer=DisplayFixture();val session=RobotSessionFixture().apply{display=peer}
        val model=testConsoleModel(robotRuntime=powerRuntime(listOf(session)))
        suspend fun settled()=withTimeout(5000){while(model.state.value.busy || model.robotDisplay.state.value.busy)delay(5)}
        try{
            model.connect("127.0.0.1","12345678");settled()
            model.remoteInput.value=listOf(.3,.1,0.0,0.0,0.0)
            model.robotDisplay.refreshHeadlights();settled()
            assertEquals(.3,model.remoteInput.value.first())
            model.robotDisplay.setHeadlights(20);settled()
            assertTrue(model.remoteInput.value.all{it==0.0});assertEquals(1,peer.writes)
            model.state.value=model.state.value.copy(scriptRunPhase=ScriptRunPhase.RUNNING)
            model.robotDisplay.setSight(SightCalibration(SightPoint(5200,5100)));settled()
            assertEquals(1,peer.writes);assertNull(model.robotDisplay.state.value.confirmed)
            model.state.value=model.state.value.copy(scriptRunPhase=ScriptRunPhase.IDLE)
            model.setForeground(false);model.robotDisplay.setHeadlights(30);settled()
            assertEquals(1,peer.writes)
            model.setForeground(true);model.robotDisplay.setHeadlights(30);settled()
            assertEquals(HeadlightBrightness(30,30),model.robotDisplay.state.value.headlights)
        }finally{model.close()}
    }

    @Test fun pauseAndReconnectionClearSnapshotsAndNeverReplayAnUnconfirmedSetting(): Unit = runBlocking {
        val peers=List(2){DisplayFixture()};val sessions=peers.map{peer->RobotSessionFixture().apply{display=peer}}
        val model=testConsoleModel(robotRuntime=powerRuntime(sessions));val gate=CompletableDeferred<Unit>()
        suspend fun until(test:()->Boolean)=withTimeout(5000){while(!test())delay(5)}
        try{
            model.connect("127.0.0.1","12345678");until{model.state.value.connected && !model.state.value.busy && model.robotDisplay.state.value.sight!=null}
            peers[0].write={callback->callback();gate.await()}
            model.robotDisplay.setSight(SightCalibration(SightPoint(5100,5200)))
            until{peers[0].writes==1}
            model.pauseConnection()
            assertEquals(DisplayTarget.SIGHT,model.robotDisplay.state.value.uncertain)
            assertNull(model.robotDisplay.state.value.sight);assertFalse(model.robotDisplay.state.value.connected)
            model.connect("127.0.0.1","12345678");until{model.state.value.connected && !model.state.value.busy && model.robotDisplay.state.value.sight!=null}
            gate.complete(Unit)
            assertEquals(SightCalibration(),model.robotDisplay.state.value.sight)
            assertNull(model.robotDisplay.state.value.uncertain);assertNull(model.robotDisplay.state.value.confirmed)
            assertEquals(0,peers[1].writes)
        }finally{model.close()}
    }
}
