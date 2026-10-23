package cn.elonzh.hanppie.robot.display

import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.protocol.hexBytes
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import kotlinx.coroutines.*
import kotlin.test.*

class DisplayCommandsTest {
    private data class Call(val node: Int, val set: Int, val id: Int, val payload: String)
    private class Peer {
        val calls = mutableListOf<Call>()
        var sight = "88137017a00f8813" // zoom-in (5000,6000), zoom-out (4000,5000).
        var pwm = "0001000200030004000a001400" // six channels, right=10, left=20.
        var reply: suspend (Call, ByteArray) -> ByteArray = { _, bytes -> bytes }
        val service = DisplayCommands { node, set, id, payload, dispatched ->
            val call = Call(node, set, id, payload.hex()); calls += call
            assertEquals(0x3f, set)
            val bytes = when (node to id) {
                9 to 0x57 -> { assertTrue(payload.isEmpty()); "00$sight".hexBytes() }
                9 to 0x56 -> { dispatched(); sight = call.payload; byteArrayOf(0) }
                0xc3 to 0x3d -> { assertTrue(payload.isEmpty()); pwm.hexBytes() }
                0xc3 to 0x3c -> {
                    dispatched()
                    // Peer only changes the channel selected by the native mask.
                    pwm = when (call.payload.take(2)) {
                        "20" -> pwm.take(22) + call.payload.drop(22)
                        "10" -> pwm.take(18) + call.payload.substring(18, 22) + pwm.drop(22)
                        else -> error("Unexpected mask")
                    }
                    byteArrayOf(0)
                }
                else -> error("Unexpected route $call")
            }
            reply(call, bytes)
        }
    }

    @Test fun queriesDecodeIndependentSightPairsAndSixPwmChannels(): Unit = runBlocking {
        val peer = Peer()
        assertEquals(SightCalibration(SightPoint(5000,6000),SightPoint(4000,5000)),peer.service.readSight())
        assertEquals(HeadlightBrightness(20,10),peer.service.readHeadlights())
        assertEquals(listOf(Call(9,0x3f,0x57,""),Call(0xc3,0x3f,0x3d,"")),peer.calls)
        peer.pwm="000000000000000000fffffeff"
        assertEquals(HeadlightBrightness(65534,65535),peer.service.readHeadlights())
    }

    @Test fun writesAllFourSightCoordinatesAndRequiresReadback(): Unit = runBlocking {
        val peer=Peer(); var sent=0
        val value=SightCalibration(SightPoint(4999,5001),SightPoint(3750,6250))
        assertEquals(value,peer.service.setSight(value){sent++})
        assertEquals(1,sent)
        assertEquals(listOf(Call(9,0x3f,0x56,"87138913a60e6a18"),Call(9,0x3f,0x57,"")),peer.calls)
        peer.reply={call,bytes -> if(call.id==0x57) "008813881388138813".hexBytes() else bytes}
        assertEquals(RequestProblem.NOT_APPLIED,assertFailsWith<RobotRequestException>{peer.service.setSight(value){}}.problem)
        assertFailsWith<IllegalArgumentException>{SightPoint(-1,5000)}
        assertFailsWith<IllegalArgumentException>{SightPoint(5000,10001)}
    }

    @Test fun nativeLeftThenRightWritesPreserveUnselectedChannelsAndConfirmBoth(): Unit = runBlocking {
        val peer=Peer();var sent=0
        assertEquals(HeadlightBrightness(75,75),peer.service.setHeadlights(75){sent++})
        assertEquals(2,sent)
        assertEquals(listOf(Call(0xc3,0x3f,0x3c,"20000000000000000000004b00"),
            Call(0xc3,0x3f,0x3c,"1000000000000000004b000000"),Call(0xc3,0x3f,0x3d,"")),peer.calls)
        assertEquals("0001000200030004004b004b00",peer.pwm)
        for(bad in listOf(-1,101)) assertFailsWith<IllegalArgumentException>{peer.service.setHeadlights(bad){fail("invalid write")}}
        assertEquals(3,peer.calls.size)
    }

    @Test fun malformedRepliesAndOutOfImageCoordinatesAreRejected(): Unit = runBlocking {
        for(bytes in listOf("", "00", "00881388138813", "001127881388138813", "00881388138813881300")) {
            val peer=Peer().apply{reply={_,_->bytes.hexBytes()}}
            assertEquals(RequestProblem.INVALID_RESPONSE,assertFailsWith<RobotRequestException>{peer.service.readSight()}.problem)
        }
        for(size in listOf(0,1,12,14)) {
            val peer=Peer().apply{reply={_,_->ByteArray(size)}}
            assertEquals(RequestProblem.INVALID_RESPONSE,assertFailsWith<RobotRequestException>{peer.service.readHeadlights()}.problem)
        }
    }

    @Test fun rejectionBeforeFirstAckDiffersFromPartialOrUnconfirmedPair(): Unit = runBlocking {
        val first=Peer().apply{reply={_,_->byteArrayOf(7)}}
        assertEquals(RequestProblem.REJECTED,assertFailsWith<RobotRequestException>{first.service.setHeadlights(20){}}.problem)
        assertEquals(1,first.calls.size)
        val second=Peer().apply{reply={call,bytes->if(call.id==0x3c && call.payload.startsWith("10"))byteArrayOf(7)else bytes}}
        assertEquals(RequestProblem.NOT_APPLIED,assertFailsWith<RobotRequestException>{second.service.setHeadlights(20){}}.problem)
        assertEquals(2,second.calls.size)
        val read=Peer().apply{reply={call,bytes->if(call.id==0x3d)byteArrayOf(7)else bytes}}
        assertEquals(RequestProblem.NOT_APPLIED,assertFailsWith<RobotRequestException>{read.service.setHeadlights(20){}}.problem)
        val sightFirst=Peer().apply{reply={_,_->byteArrayOf(7)}}
        assertEquals(RequestProblem.REJECTED,assertFailsWith<RobotRequestException>{sightFirst.service.setSight(SightCalibration()){}}.problem)
        assertEquals(1,sightFirst.calls.size)
        val sightRead=Peer().apply{reply={call,bytes->if(call.id==0x57)byteArrayOf(7)else bytes}}
        assertEquals(RequestProblem.NOT_APPLIED,assertFailsWith<RobotRequestException>{sightRead.service.setSight(SightCalibration()){}}.problem)
        assertEquals(2,sightRead.calls.size)
        val mismatch=Peer().apply{reply={call,bytes->if(call.id==0x3d) "0001000200030004000a001400".hexBytes() else bytes}}
        assertEquals(RequestProblem.NOT_APPLIED,assertFailsWith<RobotRequestException>{mismatch.service.setHeadlights(20){}}.problem)
    }

    @Test fun cancellationCloseAndConcurrencyCannotContinueAWriteSequence(): Unit = runBlocking {
        val peer=Peer();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        peer.reply={_,bytes->entered.complete(Unit);release.await();bytes}
        val writing=async{peer.service.setHeadlights(40){}}
        entered.await()
        assertFailsWith<IllegalStateException>{peer.service.readSight()}
        writing.cancelAndJoin();release.complete(Unit)
        assertEquals(1,peer.calls.size)
        peer.reply={_,bytes->peer.service.close();bytes}
        assertEquals(RequestProblem.DISCONNECTED,assertFailsWith<RobotRequestException>{peer.service.readSight()}.problem)
        val before=peer.calls.size
        assertEquals(RequestProblem.DISCONNECTED,assertFailsWith<RobotRequestException>{peer.service.setSight(SightCalibration()){}}.problem)
        assertEquals(before,peer.calls.size)
    }
}
