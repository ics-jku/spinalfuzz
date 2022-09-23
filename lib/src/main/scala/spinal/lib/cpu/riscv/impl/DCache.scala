package spinal.lib.cpu.riscv.impl

import java.text.AttributedString


import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4Shared, Axi4Config}
import spinal.lib.bus.avalon.{AvalonMM, AvalonMMConfig}


case class DataCacheConfig( cacheSize : Int,
                            bytePerLine : Int,
                            wayCount : Int,
                            addressWidth : Int,
                            cpuDataWidth : Int,
                            memDataWidth : Int){
  def burstSize = bytePerLine*8/memDataWidth
  def getAvalonConfig() = AvalonMMConfig.bursted(
      addressWidth = addressWidth,
      dataWidth = memDataWidth,
    burstCountWidth = log2Up(burstSize + 1)).copy(
      useByteEnable = true,
      constantBurstBehavior = true,
      burstOnBurstBoundariesOnly = true,
      maximumPendingReadTransactions = 2
    )

  def getAxi4SharedConfig() = Axi4Config(
    addressWidth = addressWidth,
    dataWidth = 32,
    useId = false,
    useRegion = false,
    useBurst = false,
    useLock = false,
    useQos = false,
    useLen = false,
    useResp = false
  )


  val burstLength = bytePerLine/(memDataWidth/8)
}

object DataCacheCpuCmdKind extends SpinalEnum{
  val MEMORY,FLUSH,EVICT = newElement()
}

case class DataCacheCpuCmd()(implicit p : DataCacheConfig) extends Bundle{
  val kind = DataCacheCpuCmdKind()
  val wr = Bool
  val address = UInt(p.addressWidth bit)
  val data = Bits(p.cpuDataWidth bit)
  val mask = Bits(p.cpuDataWidth/8 bit)
  val bypass = Bool
  val all = Bool                      //Address should be zero when "all" is used

}
case class DataCacheCpuRsp()(implicit p : DataCacheConfig) extends Bundle{
  val data = Bits(p.cpuDataWidth bit)
}

case class DataCacheCpuBus()(implicit p : DataCacheConfig) extends Bundle with IMasterSlave{
  val cmd = Stream (DataCacheCpuCmd())
  val rsp = Flow (DataCacheCpuRsp())

  override def asMaster(): Unit = {
    master(cmd)
    slave(rsp)
  }
}


case class DataCacheMemCmd()(implicit p : DataCacheConfig) extends Bundle{
  val wr = Bool
  val address = UInt(p.addressWidth bit)
  val data = Bits(p.memDataWidth bits)
  val mask = Bits(p.memDataWidth/8 bits)
  val length = UInt(log2Up(p.burstLength+1) bit)
}
case class DataCacheMemRsp()(implicit p : DataCacheConfig) extends Bundle{
  val data = Bits(p.memDataWidth bit)
}

case class DataCacheMemBus()(implicit p : DataCacheConfig) extends Bundle with IMasterSlave{
  val cmd = Stream (DataCacheMemCmd())
  val rsp = Flow (DataCacheMemRsp())

  override def asMaster(): Unit = {
    master(cmd)
    slave(rsp)
  }


  def toAvalon(): AvalonMM = {
    val avalonConfig = p.getAvalonConfig()
    val mm = AvalonMM(avalonConfig)
    mm.read := cmd.valid && !cmd.wr
    mm.write := cmd.valid && cmd.wr
    mm.address := cmd.address(cmd.address.high downto log2Up(p.memDataWidth/8)) @@ U(0,log2Up(p.memDataWidth/8) bits)
    mm.burstCount := cmd.length
    mm.byteEnable := cmd.mask
    mm.writeData := cmd.data

    cmd.ready := mm.waitRequestn
    rsp.valid := mm.readDataValid
    rsp.data := mm.readData
    mm
  }

  def toAxi4Shared() : Axi4Shared = {
    val axiConfig = p.getAxi4SharedConfig()
    val mm = Axi4Shared(axiConfig)

    ???

//
//    mm.sharedCmd.valid := cmd.valid
//    mm.sharedCmd.write := cmd.wr
//    mm.sharedCmd.addr := cmd.address(cmd.address.high downto log2Up(p.memDataWidth/8)) @@ U(0,log2Up(p.memDataWidth/8) bits)
//    mm.sharedCmd.len := cmd.length-1
//    mm.byteEnable := cmd.mask
//    mm.writeData := cmd.data
//
//    cmd.ready := mm.readwaitRequestn
//    rsp.valid := mm.readDataValid
//    rsp.data := mm.readData
    mm
  }
}


class DataCache(implicit p : DataCacheConfig) extends Component{
  import p._
  assert(wayCount == 1)
  assert(cpuDataWidth == memDataWidth)
  val io = new Bundle{
    val cpu = slave(DataCacheCpuBus())
    val mem = master(DataCacheMemBus())
    val flushDone = out Bool //It pulse at the same time than the manager.request.fire
  }
  val haltCpu = False
  val lineWidth = bytePerLine*8
  val lineCount = cacheSize/bytePerLine
  val wordWidth = Math.max(memDataWidth,cpuDataWidth)
  val wordWidthLog2 = log2Up(wordWidth)
  val wordPerLine = lineWidth/wordWidth
  val bytePerWord = wordWidth/8
  val wayLineCount = lineCount/wayCount
  val wayLineLog2 = log2Up(wayLineCount)
  val wayWordCount = wayLineCount * wordPerLine
  val memTransactionPerLine = p.bytePerLine / (p.memDataWidth/8)

  val tagRange = addressWidth-1 downto log2Up(wayLineCount*bytePerLine)
  val lineRange = tagRange.low-1 downto log2Up(bytePerLine)
  val wordRange = log2Up(bytePerLine)-1 downto log2Up(bytePerWord)


  class LineInfo() extends Bundle{
    val used = Bool
    val dirty = Bool
    val address = UInt(tagRange.length bit)
  }

  val tagsReadCmd =  UInt(log2Up(wayLineCount) bits)
  val tagsWriteCmd = Flow(new Bundle{
    val way = UInt(log2Up(wayCount) bits)
    val address = UInt(log2Up(wayLineCount) bits)
    val data = new LineInfo()
  })

  val tagsWriteLastCmd = RegNext(tagsWriteCmd)

  val dataReadCmd =  UInt(log2Up(wayWordCount) bits)
  val dataWriteCmd = Flow(new Bundle{
    val way = UInt(log2Up(wayCount) bits)
    val address = UInt(log2Up(wayWordCount) bits)
    val data = Bits(wordWidth bits)
    val mask = Bits(wordWidth/8 bits)
  })

  tagsWriteCmd.valid := False
  tagsWriteCmd.payload.assignDontCare()
  dataWriteCmd.valid := False
  dataWriteCmd.payload.assignDontCare()
  io.mem.cmd.valid := False
  io.mem.cmd.payload.assignDontCare()




  val ways = Array.tabulate(wayCount)(id => new Area{
    val tags = Mem(new LineInfo(),wayLineCount)
    val data = Mem(Bits(wordWidth bit),wayWordCount) //TODO write mask

    when(tagsWriteCmd.valid && tagsWriteCmd.way === id){
      tags(tagsWriteCmd.address) := tagsWriteCmd.data
    }
    when(dataWriteCmd.valid && dataWriteCmd.way === id){
      data.write(
        address = dataWriteCmd.address,
        data = dataWriteCmd.data,
        mask = dataWriteCmd.mask
      )
    }
    val dataReadRsp = data.readSync(dataReadCmd)
  })

  val dataReadedValue = Vec.tabulate(ways.length)(id => RegNext(ways(id).dataReadRsp))




  val victim = new Area{
    val requestIn = Stream(new Bundle{
      val way = UInt(log2Up(wayCount) bits)
      val address = UInt(p.addressWidth bits)
    })
    requestIn.valid := False
    requestIn.payload.assignDontCare()

    val request = requestIn.stage()
    request.ready := False

    val buffer = Mem(Bits(p.memDataWidth bits),memTransactionPerLine << 1)  // << 1 because of cyclone II issue, should be removed //.add(new AttributeString("ramstyle","M4K"))

    //Send line read commands to fill the buffer
    val readLineCmdCounter = Reg(UInt(log2Up(memTransactionPerLine + 1) bits)) init(0)
    val dataReadCmdOccure = False
    val dataReadCmdOccureLast = RegNext(dataReadCmdOccure)

    when(request.valid && !readLineCmdCounter.msb){
      readLineCmdCounter := readLineCmdCounter + 1
      //dataReadCmd := request.address(lineRange.high downto wordRange.low)   Done in the manager
      dataReadCmdOccure := True
    }

    //Fill the buffer with line read responses
    val readLineRspCounter = Reg(UInt(log2Up(memTransactionPerLine + 1) bits)) init(0)
    when(readLineCmdCounter >= 2 && !readLineRspCounter.msb && Delay(dataReadCmdOccure,2)){
      buffer(readLineRspCounter.resized) := dataReadedValue(request.way)
      readLineRspCounter := readLineRspCounter + 1
    }

    //Send buffer read commands
    val bufferReadCounter = Reg(UInt(log2Up(memTransactionPerLine + 1) bits)) init(0)
    val bufferReadStream = Stream(buffer.addressType)
    bufferReadStream.valid := readLineRspCounter > bufferReadCounter
    bufferReadStream.payload := bufferReadCounter.resized
    when(bufferReadStream.fire){
      bufferReadCounter := bufferReadCounter + 1
    }
    val bufferReaded = buffer.streamReadSync(bufferReadStream).stage
    bufferReaded.ready := False

    //Send memory writes from bufffer read responses
    val bufferReadedCounter = Reg(UInt(log2Up(memTransactionPerLine) bits)) init(0)
    val memCmdAlreadyUsed = False
    when(bufferReaded.valid) {
      io.mem.cmd.valid := True
      io.mem.cmd.wr := True
      io.mem.cmd.address := request.address(tagRange.high downto lineRange.low) @@ U(0,lineRange.low bit)
      io.mem.cmd.length := p.burstLength
      io.mem.cmd.data := bufferReaded.payload
      io.mem.cmd.mask := (1<<(wordWidth/8))-1

      when(!memCmdAlreadyUsed && io.mem.cmd.ready){
        bufferReaded.ready := True
        bufferReadedCounter := bufferReadedCounter + 1
        when(bufferReadedCounter === bufferReadedCounter.maxValue){
          request.ready := True
        }
      }
    }


    val counter = Counter(memTransactionPerLine)
    when(request.ready){
      readLineCmdCounter.msb := False
      readLineRspCounter.msb := False
      bufferReadCounter.msb := False
    }
  }

  val manager = new Area {
    io.flushDone := False

    val request = io.cpu.cmd.haltWhen(haltCpu).stage()
    request.ready := !request.valid
    //Evict the cache after reset
    request.valid.getDrivingReg.init(True)
    request.kind.getDrivingReg.init(DataCacheCpuCmdKind.EVICT)
    request.all.getDrivingReg.init(True)
    request.address.getDrivingReg.init(0)

    val waysHitValid = False
    val waysHitOneHot = Bits(wayCount bits)
    val waysHitId = OHToUInt(waysHitOneHot)
    val waysHitInfo = new LineInfo().assignDontCare()

    //delayedXX are used to relax logic timings in flush and evict modes
    val delayedValid = RegNext(request.isStall) init(False)
    val delayedWaysHitValid = RegNext(waysHitValid)
    val delayedWaysHitId = RegNext(waysHitId)

    val waysReadAddress = Mux(request.isStall, request.address, io.cpu.cmd.address)
    tagsReadCmd := waysReadAddress(lineRange)
    dataReadCmd := waysReadAddress(lineRange.high downto wordRange.low)
    when(victim.dataReadCmdOccure){
      dataReadCmd := victim.request.address(lineRange) @@ victim.readLineCmdCounter(victim.readLineCmdCounter.high-1 downto 0)
    }
    val waysRead = for ((way,id) <- ways.zipWithIndex) yield new Area {
      val tag = way.tags.readSync(tagsReadCmd)
      //Write first
      when(tagsWriteLastCmd.valid && tagsWriteLastCmd.way === id && tagsWriteLastCmd.address === RegNext(waysReadAddress(lineRange))){
        tag := tagsWriteLastCmd.data
      }
      waysHitOneHot(id) := tag.used && tag.address === request.address(tagRange)
      when(waysHitOneHot(id)) {
        waysHitValid := True
        waysHitInfo := tag
      }
    }


    val writebackWayId = U(0) //Only one way compatible
    val writebackWayInfo = Vec(waysRead.map(_.tag))(writebackWayId)

    val cpuRspIn = Stream(new Bundle{
      val fromBypass = Bool
      val wayId = UInt(log2Up(wayCount) bits)
    })

    cpuRspIn.valid := False
    cpuRspIn.fromBypass := False
    cpuRspIn.wayId := waysHitId

    //Loader interface
    val loaderValid = False
    val loaderReady = False
    val loadingDone = RegNext(loaderValid && loaderReady) init(False) //one cycle pulse

    val victimSent = RegInit(False)
    victimSent := (victimSent || victim.requestIn.fire) && !request.ready

    val flushAllState = RegInit(False) //Used to keep logic timings fast
    val flushAllDone = RegNext(False) init(False)
    when(request.valid) {
      switch(request.kind) {
        import DataCacheCpuCmdKind._
        is(EVICT){
          when(request.all){
            tagsWriteCmd.valid := True
            tagsWriteCmd.way := 0
            tagsWriteCmd.address := request.address(lineRange)
            tagsWriteCmd.data.used := False
            when(request.address(lineRange) =/= lineCount-1){
              request.address.getDrivingReg(lineRange) := request.address(lineRange) + 1
            }otherwise{
              request.ready := True
            }
          }otherwise{
            when(delayedValid) {
              when(delayedWaysHitValid) {
                tagsWriteCmd.valid := True
                tagsWriteCmd.way := delayedWaysHitId
                tagsWriteCmd.address := request.address(lineRange)
                tagsWriteCmd.data.used := False
              }
              request.ready := True
            }
          }
        }
        is(FLUSH) {
          when(request.all) {
            when(!flushAllState){
              victim.requestIn.valid := waysRead(0).tag.used && waysRead(0).tag.dirty
              victim.requestIn.way := writebackWayId
              victim.requestIn.address := writebackWayInfo.address @@ request.address(lineRange) @@ U((lineRange.low - 1 downto 0) -> false)

              tagsWriteCmd.way := writebackWayId
              tagsWriteCmd.address := request.address(lineRange)
              tagsWriteCmd.data.used := False

              when(!victim.requestIn.isStall) {
                request.address.getDrivingReg(lineRange) := request.address(lineRange) + 1
                flushAllDone :=  request.address(lineRange) === lineCount-1
                flushAllState := True
                tagsWriteCmd.valid := True
              }
            } otherwise{
              //Wait tag read
              flushAllState := False
              request.ready := flushAllDone
              io.flushDone := flushAllDone
            }
          } otherwise {
            when(delayedValid) {
              when(delayedWaysHitValid) {
                request.ready := victim.requestIn.ready

                victim.requestIn.valid := True
                victim.requestIn.way := writebackWayId
                victim.requestIn.address := writebackWayInfo.address @@ request.address(lineRange) @@ U((lineRange.low - 1 downto 0) -> false)

                tagsWriteCmd.valid := victim.requestIn.ready
                tagsWriteCmd.way := writebackWayId
                tagsWriteCmd.address := request.address(lineRange)
                tagsWriteCmd.data.used := False
              } otherwise{
                request.ready := True
                io.flushDone := True
              }
            }
          }
        }
        is(MEMORY) {
          when(request.bypass) {
            when(!victim.request.valid) {
              //Can't insert mem cmd into a victim write burst
              io.mem.cmd.valid := !(!request.wr && !cpuRspIn.ready)
              io.mem.cmd.wr := request.wr
              io.mem.cmd.address := request.address(tagRange.high downto wordRange.low) @@ U(0,wordRange.low bit)
              io.mem.cmd.mask := request.mask
              io.mem.cmd.data := request.data
              io.mem.cmd.length := 1

              cpuRspIn.valid := !request.wr && io.mem.cmd.fire
              cpuRspIn.fromBypass := True

              request.ready := io.mem.cmd.fire
            } otherwise {
              request.ready := False
            }
          } otherwise {
            when(waysHitValid && !loadingDone) { // !loadingDone => don't solve the request directly after loader (data write to read latency)
              when(request.wr) {
                dataWriteCmd.valid := True
                dataWriteCmd.way := waysHitId
                dataWriteCmd.address := request.address(lineRange.high downto wordRange.low)
                dataWriteCmd.data := request.data
                dataWriteCmd.mask := request.mask

                tagsWriteCmd.valid := True
                tagsWriteCmd.way := waysHitId
                tagsWriteCmd.address := request.address(lineRange)
                tagsWriteCmd.data.used := True
                tagsWriteCmd.data.dirty := True
                tagsWriteCmd.data.address := request.address(tagRange)
                request.ready := True
              } otherwise {
                cpuRspIn.valid := !victim.dataReadCmdOccureLast
                request.ready := cpuRspIn.ready && !victim.dataReadCmdOccureLast //dataReadCmdOccure to avoid the case where flush,then read will victim use data read
              }
            } otherwise {
              request.ready := False //Exit this state automaticly (tags read port write first logic)
              loaderValid := !loadingDone && !(!victimSent && victim.request.isStall) //Wait previous victim request to be completed
              when(writebackWayInfo.used && writebackWayInfo.dirty) {
                victim.requestIn.valid := !victimSent
                victim.requestIn.way := writebackWayId
                victim.requestIn.address := writebackWayInfo.address @@ request.address(lineRange) @@ U((lineRange.low - 1 downto 0) -> false)
              }
            }
          }
        }
      }
    }


    val cpuRsp = cpuRspIn.m2sPipe()
    val cpuRspIsWaitingMemRsp = cpuRsp.valid && io.mem.rsp.valid
    io.cpu.rsp.valid := cpuRsp.fire
    io.cpu.rsp.data := Mux(cpuRsp.fromBypass,io.mem.rsp.data,dataReadedValue(cpuRsp.wayId))
    cpuRsp.ready := !(cpuRsp.fromBypass && !io.mem.rsp.valid)
  }

  //The whole life of a loading task, the corresponding manager request is present
  val loader = new Area{
    val valid = RegNext(manager.loaderValid)
    val wayId = RegNext(manager.writebackWayId)
    val baseAddress =  manager.request.address

    val memCmdSent = RegInit(False)
    when(valid && !memCmdSent) {
      io.mem.cmd.valid := True
      io.mem.cmd.wr := False
      io.mem.cmd.address := baseAddress(tagRange.high downto lineRange.low) @@ U(0,lineRange.low bit)
      io.mem.cmd.length := p.burstLength
    }

    when(valid && io.mem.cmd.ready){
      memCmdSent := True
    }

    when(valid && !memCmdSent) {
      victim.memCmdAlreadyUsed := True
    }

    val counter = Counter(memTransactionPerLine)
    when(io.mem.rsp.valid && !manager.cpuRspIsWaitingMemRsp){
      dataWriteCmd.valid := True
      dataWriteCmd.way := wayId
      dataWriteCmd.address := baseAddress(lineRange) @@ counter
      dataWriteCmd.data := io.mem.rsp.data
      dataWriteCmd.mask := (1<<(wordWidth/8))-1
      counter.increment()
    }

    when(counter.willOverflow){
      memCmdSent := False
      valid := False
      tagsWriteCmd.valid := True
      tagsWriteCmd.way := wayId
      tagsWriteCmd.address := baseAddress(lineRange)
      tagsWriteCmd.data.used := True
      tagsWriteCmd.data.dirty := False
      tagsWriteCmd.data.address := baseAddress(tagRange)
      manager.loaderReady := True
    }
  }

  //Avoid read after write data hazard
  when(io.cpu.cmd.address === manager.request.address && manager.request.valid && io.cpu.cmd.valid){
    haltCpu := True
  }
}

object DataCacheMain{
  def main(args: Array[String]) {

    SpinalVhdl({
      implicit val p = DataCacheConfig(
        cacheSize =4096,
        bytePerLine =32,
        wayCount = 1,
        addressWidth = 32,
        cpuDataWidth = 32,
        memDataWidth = 32)
      new WrapWithReg.Wrapper(new DataCache()(p)).setDefinitionName("TopLevel")
    })
    SpinalVhdl({
      implicit val p = DataCacheConfig(
        cacheSize =512,
        bytePerLine =16,
        wayCount = 1,
        addressWidth = 12,
        cpuDataWidth = 16,
        memDataWidth = 16)
      new DataCache()(p)
    })
  }
}