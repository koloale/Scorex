package scorex.network.peer

import java.net.InetSocketAddress

import akka.actor.Props
import akka.pattern.ask
import akka.testkit.TestProbe
import scorex.ActorTestingCommons
import scorex.app.ApplicationVersion
import scorex.network.NetworkController.SendToNetwork
import scorex.network.PeerConnectionHandler.CloseConnection
import scorex.network._
import scorex.network.message.Message
import scorex.network.message.MessageHandler.RawNetworkData
import scorex.network.peer.PeerManager.Connected
import scorex.settings.SettingsMock

import scala.concurrent.Await
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.util.Left

class PeerManagerSpecification extends ActorTestingCommons {

  import PeerManager._

  val hostname = "localhost"
  val knownAddress = new InetSocketAddress(hostname, 6789)

  object TestSettings extends SettingsMock {
    override lazy val dataDirOpt: Option[String] = None
    override lazy val knownPeers: Seq[InetSocketAddress] = Seq(knownAddress)
    override lazy val nodeNonce: Long = 123456789
    override lazy val maxConnections: Int = 10
    override lazy val peersDataResidenceTime: FiniteDuration = 100 seconds
    override lazy val blacklistResidenceTimeMilliseconds: Long = 1000
  }

  trait App extends ApplicationMock {
    override lazy val settings = TestSettings
    override val applicationName: String = "test"
    override val appVersion: ApplicationVersion = ApplicationVersion(7, 7, 7)
  }

  private val app = stub[App]

  import app.basicMessagesSpecsRepo._

  protected override val actorRef = system.actorOf(Props(classOf[PeerManager], app))

  testSafely {

    val nonce = 777

    val peerConnectionHandler = TestProbe("connection-handler")

    def connect(address: InetSocketAddress, noneNonce: Long): Unit = {
      actorRef ! Connected(address, peerConnectionHandler.ref, None)
      peerConnectionHandler.expectMsgType[Handshake]
      actorRef ! Handshaked(address, Handshake("scorex", ApplicationVersion(0, 0, 0), "", noneNonce, None, 0))
    }

    def getConnectedPeers =
      Await.result((actorRef ? GetConnectedPeers).mapTo[Seq[(InetSocketAddress, Handshake)]], testDuration)

    def getBlacklistedPeers =
      Await.result((actorRef ? GetBlacklistedPeers).mapTo[Set[String]], testDuration)

    def getActiveConnections =
      Await.result((actorRef ? GetConnections).mapTo[Seq[InetSocketAddress]], testDuration)

    val anAddress = new InetSocketAddress(hostname, knownAddress.getPort + 1)

    "blacklisting" in {
      actorRef ! CheckPeers
      networkController.expectMsg(NetworkController.ConnectTo(knownAddress))
      connect(knownAddress, nonce)

      actorRef ! AddToBlacklist(nonce, knownAddress)
      peerConnectionHandler.expectMsg(CloseConnection)

      actorRef ! Disconnected(knownAddress)
      getActiveConnections shouldBe empty

      val t = (TestSettings.blacklistResidenceTimeMilliseconds / 2) millis

      actorRef ! CheckPeers

      networkController.expectNoMsg(t)

      val anotherAddress = new InetSocketAddress(knownAddress.getHostName, knownAddress.getPort + 1)
      val anotherPeerHandler = TestProbe("connection-handler-2")

      actorRef ! Connected(anotherAddress, anotherPeerHandler.ref, null)

      anotherPeerHandler.expectMsg(CloseConnection)
      anotherPeerHandler.expectNoMsg(t)

      actorRef ! CheckPeers
      networkController.expectMsg(NetworkController.ConnectTo(knownAddress))

      actorRef ! Connected(anotherAddress, anotherPeerHandler.ref, null)
      anotherPeerHandler.expectMsgType[Handshake]
    }

    "peer cycle" - {
      connect(anAddress, nonce)

      val connectedPeers = getConnectedPeers
      val (addr, Handshake(_, _, _, nodeNonce, _, _)) = connectedPeers.head

      "connect - disconnect" in {
        connectedPeers.size shouldBe 1

        addr shouldEqual anAddress
        nodeNonce shouldEqual nonce

        actorRef ! Disconnected(anAddress)
        getConnectedPeers shouldBe empty
      }

      "double connect" in {

        actorRef ! CheckPeers

        networkController.expectMsg(NetworkController.ConnectTo(knownAddress))

        connect(knownAddress, nonce)

        getConnectedPeers.size shouldBe 1

        actorRef ! CheckPeers

        networkController.expectNoMsg(testDuration)

        getConnectedPeers.size shouldBe 1

        actorRef ! Disconnected(anAddress)
        getConnectedPeers shouldBe empty
        getActiveConnections shouldBe empty
      }

      "msg from network routing" - {
        val rawData = RawNetworkData(BlockMessageSpec, Array[Byte](23), anAddress)
        actorRef ! rawData

        def assertThatMessageGotten(): Unit = networkController.expectMsgPF() {
          case Message(spec, Left(bytes), Some(p)) =>
            spec shouldEqual rawData.spec
            bytes shouldEqual rawData.data
            p.nonce shouldEqual nodeNonce
        }

        "msg is gotten" in {
          assertThatMessageGotten()
        }

        "surviving reconnect" in {
          actorRef ! Disconnected(anAddress)

          val sameClientAnotherPort = new InetSocketAddress(hostname, knownAddress.getPort + 2)
          connect(sameClientAnotherPort, nonce)

          assertThatMessageGotten()
        }
      }

      "msg to network routing" in {
        val p = mock[ConnectedPeer]
        p.nonce _ expects() returns nonce

        val msg = Message(BlockMessageSpec, Left(Array[Byte](27)), None)
        actorRef ! SendToNetwork(msg, SendToChosen(p))

        peerConnectionHandler.expectMsg(msg)
      }

      "add to blacklist" in {
        actorRef ! AddToBlacklist(nonce, anAddress)

        getBlacklistedPeers should have size 1

        actorRef ! AddToBlacklist(nonce + 1, new InetSocketAddress(anAddress.getHostName, anAddress.getPort + 1))

        getBlacklistedPeers should have size 1
        getBlacklistedPeers should contain (anAddress.getHostName)
      }
    }

    "connect to self is forbidden" in {
      connect(new InetSocketAddress("localhost", 45980), TestSettings.nodeNonce)
      peerConnectionHandler.expectMsg(CloseConnection)
      getActiveConnections shouldBe empty
    }

    "many TCP clients with same nonce" in {

      def connect(id: Int): TestProbe = {
        val address = new InetSocketAddress(id)
        val handler = TestProbe("connection-handler-" + id)
        actorRef ! Connected(address, handler.ref, None)
        handler.expectMsgType[Handshake]
        actorRef ! Handshaked(address, Handshake("scorex", ApplicationVersion(0, 0, 0), "", nonce, None, 0))
        handler
      }

      val h1 = connect(1)
      getConnectedPeers should have size 1

      val h2 = connect(2)
      h2.expectMsg(CloseConnection)
      getConnectedPeers should have size 1

      val h3 = connect(3)

      h1.expectMsg(CloseConnection)
      h3.expectMsg(CloseConnection)
    }

    "disconnect during handshake" in {
      actorRef ! Connected(anAddress, peerConnectionHandler.ref, None)
      peerConnectionHandler.expectMsgType[Handshake]

      getActiveConnections should have size 1

      actorRef ! Disconnected(anAddress)
      getConnectedPeers shouldBe empty
      getActiveConnections shouldBe empty
    }

    "PeerManager returns on GetConnectedPeers list of pairs (InetSocketAddress, Handshake)" in {
      assert(getConnectedPeers.isEmpty)

      // prepare
      connect(anAddress, 655)

      // assert
      val result2 = getConnectedPeers
      assert(result2.nonEmpty)
      val (a, h) = result2.head
      assert(a == anAddress)
      assert(h.applicationName == "scorex")
    }

    "get random peers" in {
      actorRef ! AddOrUpdatePeer(new InetSocketAddress(99), None, None)
      actorRef ! AddOrUpdatePeer(new InetSocketAddress(100), Some(TestSettings.nodeNonce), None)

      val addr = new InetSocketAddress(101)
      actorRef ! AddOrUpdatePeer(addr, None, None)
      connect(addr, 11)

      connect(new InetSocketAddress(56099), 56099)

      val peers = Await.result((actorRef ? GetRandomPeersToBroadcast(3)).mapTo[Seq[InetSocketAddress]], testDuration)

      peers should have size 1
      peers.head shouldBe addr
    }

    "blacklist nonconnected peer" in {
      getBlacklistedPeers shouldBe empty

      actorRef ! AddToBlacklist(-111, knownAddress)

      getBlacklistedPeers.size shouldBe 1
    }
  }
}
