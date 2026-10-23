package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.display.*
import cn.elonzh.hanppie.robot.protocol.*
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

class RobotDisplaySessionTest {
    @Test fun brokerConfirmsNativeDisplayWritesAndReadOnlyQueriesDoNotNeedRemoteOwnership(): Unit = runBlocking {
        val socket=DatagramSocket(0,InetAddress.getLoopbackAddress()).apply{soTimeout=30}
        val running=AtomicBoolean(true);val calls=CopyOnWriteArrayList<DussFrame>()
        val worker=thread(isDaemon=true){
            var sight="8813881388138813".hexBytes()
            val pwm="00010002000300040014002800".hexBytes()
            while(running.get())try{
                val packet=DatagramPacket(ByteArray(65535),65535);socket.receive(packet)
                val bytes=packet.data.copyOf(packet.length)
                socket.send(DatagramPacket(bytes,bytes.size,packet.socketAddress))
                for(frame in Protocol.frames(bytes)){
                    val reply=when {
                        frame.receiver==9 && frame.set==0x3f && frame.id==0x57 -> byteArrayOf(0)+sight
                        frame.receiver==9 && frame.set==0x3f && frame.id==0x56 -> {sight=frame.payload.copyOf();byteArrayOf(0)}
                        frame.receiver==0xc3 && frame.set==0x3f && frame.id==0x3d -> pwm.copyOf()
                        frame.receiver==0xc3 && frame.set==0x3f && frame.id==0x3c -> {
                            val offset=if(frame.payload[0].toInt()==0x20)11 else 9
                            frame.payload.copyInto(pwm,offset,offset,offset+2);byteArrayOf(0)
                        }
                        else -> continue
                    }
                    calls+=frame
                    for((sender,payload)in listOf(0x28 to byteArrayOf(9),frame.receiver to reply)){
                        val response=AppEnvelope(bytes.u16(2),80).direct(Protocol.duss(sender,2,0xc0,frame.set,frame.id,payload,frame.sequence))
                        socket.send(DatagramPacket(response,response.size,packet.socketAddress))
                    }
                }
            }catch(_:SocketTimeoutException){}catch(_:SocketException){break}
        }
        try{
            AppSession(RobotTarget("127.0.0.1","12345678",localPort=0,remotePort=socket.localPort)).use{session->
                session.connect()
                assertEquals(SightCalibration(),session.display.readSight())
                assertEquals(HeadlightBrightness(40,20),session.display.readHeadlights())
                val value=SightCalibration(SightPoint(5010,4990),SightPoint(5200,4800))
                assertEquals(value,session.display.setSight(value){})
                assertEquals("92137e135014c012",calls.single{it.id==0x56}.payload.hex())
                assertEquals(HeadlightBrightness(60,60),session.display.setHeadlights(60){})
                assertEquals(listOf("20000000000000000000003c00","1000000000000000003c000000"),calls.filter{it.id==0x3c}.map{it.payload.hex()})
                assertTrue(calls.all{it.sender==2 && it.attr==0x40})
                session.enterRemote()
                assertEquals(value,session.display.readSight())
                val before=calls.count{it.id==0x56 || it.id==0x3c}
                assertFailsWith<IllegalStateException>{session.display.setSight(SightCalibration()){fail("remote write")}}
                assertFailsWith<IllegalStateException>{session.display.setHeadlights(10){fail("remote write")}}
                assertEquals(before,calls.count{it.id==0x56 || it.id==0x3c})
            }
        }finally{running.set(false);socket.close();worker.join(1000)}
    }
}
