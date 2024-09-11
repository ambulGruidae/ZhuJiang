package xijiang

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import xijiang.c2c.{C2cLinkPort, C2cPackLayer}
import xijiang.router._
import xijiang.router.base.IcnBundle
import zhujiang.{ZJBundle, ZJModule, ZJParametersKey}
import xijiang.tfs._

class TfsIO(local: Boolean)(implicit p: Parameters) extends ZJBundle {
  private val ring = if(local) p(ZJParametersKey).localRing else p(ZJParametersKey).csnRing
  private val c2cNum = ring.count(_.nodeType == NodeType.C)
  val remoteChip = Input(Vec(c2cNum, UInt(chipAddrBits.W)))
}

class Ring(local: Boolean)(implicit p: Parameters) extends ZJModule {
  private val tfs = p(ZJParametersKey).tfsParams.isDefined
  private val ringName = if(local) "LocalRing" else "CsnRing"
  override val desiredName = ringName
  val tfsio = if(tfs) Some(IO(new TfsIO(local))) else None
  val io_chip = IO(Input(UInt(chipAddrBits.W)))

  tfsio.foreach(dontTouch(_))
  dontTouch(io_chip)

  private val ring = if(local) p(ZJParametersKey).localRing else p(ZJParametersKey).csnRing
  private val routersAndNodes = ring.map(n => (n.genRouter(p), n))
  for(((r, n), i) <- routersAndNodes.zipWithIndex) {
    val left = if(i == 0) routersAndNodes.last else routersAndNodes(i - 1)
    val right = if(i == routersAndNodes.size - 1) routersAndNodes.head else routersAndNodes(i + 1)
    r.router.rings.head.rx := left._1.router.rings.head.tx
    r.router.chip := io_chip
    if(local) r.router.rings.last.rx := right._1.router.rings.last.tx
  }

  private val chiRoutersAndNodes = routersAndNodes.filterNot(_._2.nodeType == NodeType.C).filterNot(_._2.nodeType == NodeType.P)
  private val c2cRoutersAndNodes = routersAndNodes.filter(_._2.nodeType == NodeType.C)

  private val icnsWithNodes = for((r, n) <- chiRoutersAndNodes) yield {
    if(tfs) {
      val m = Module(new TrafficGen(n))
      m.icn <> r.icn
      m.nodeId := r.router.nodeId
      (None, n)
    } else {
      val port = IO(new IcnBundle(n)(p))
      port.suggestName(n.icnStr(local))
      port <> r.icn
      (Some(port), n)
    }
  }

  private val c2csWithNodes = for(((r, n), i) <- c2cRoutersAndNodes.zipWithIndex) yield {
    if(tfs) {
      val m = Module(new TrafficGen(n))
      m.icn <> r.icn
      m.nodeId := r.router.nodeId
      r.router.chip := tfsio.get.remoteChip(i)
      (None, n)
    } else {
      val c2cPacker = Module(new C2cPackLayer(n))
      c2cPacker.io.local.icn <> r.icn
      r.router.chip := c2cPacker.io.local.chip
      c2cPacker.suggestName("c2cPackLayer")
      val port = IO(new C2cLinkPort)
      port.suggestName(s"c2c_$i")
      port <> r.icn
      (Some(port), n)
    }
  }

  val icnRfs = if(!tfs) Some(icnsWithNodes.filter(_._2.nodeType == NodeType.RF).map(_._1.get)) else None
  val icnRis = if(!tfs) Some(icnsWithNodes.filter(_._2.nodeType == NodeType.RI).map(_._1.get)) else None
  val icnHfs = if(!tfs) Some(icnsWithNodes.filter(_._2.nodeType == NodeType.HF).map(_._1.get)) else None
  val icnHis = if(!tfs) Some(icnsWithNodes.filter(_._2.nodeType == NodeType.HI).map(_._1.get)) else None
  val icnSns = if(!tfs) Some(icnsWithNodes.filter(_._2.nodeType == NodeType.S).map(_._1.get)) else None

  val c2cs = if(!tfs) Some(c2csWithNodes.map(_._1.get)) else None

  private val functionalRouters = routersAndNodes.filter(_._2.nodeType != NodeType.P).map(_._1)
  for(i <- functionalRouters.indices) {
    for(j <- (i + 1) until functionalRouters.size) {
      if(local) {
        require(functionalRouters(i).node.nodeId != functionalRouters(j).node.nodeId)
      } else {
        assert(functionalRouters(i).router.nodeId =/= functionalRouters(j).router.nodeId)
      }
    }
  }
}
