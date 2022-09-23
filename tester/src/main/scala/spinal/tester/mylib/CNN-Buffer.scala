package mylib

import spinal.core._
import spinal.lib._
import spinal.core.GenerationFlags._
import spinal.core.Formal._
import spinal.lib.fsm._
import spinal.lib.Counter._

class CnnBuffer(iShape: (Int, Int, Int)) extends Component {
  val io = new Bundle {
    val in = slave Stream(Fragment(Bits(iShape._3 bit)))
    val out = master Stream(Fragment(Vec(Bits(4 bits), iShape._3)))
  }

  val buffer = Vec(Reg(Bits(iShape._1 + 2 bits)), iShape._3)
  val colCounter = Counter(0, iShape._1 - 1)
  val rowCounter = Counter(0, iShape._2 - 2)
  val loadEn = Bool
  // sample if last was set on input fragment
  val wasInLast = Reg(Bool) init(False)
  wasInLast := wasInLast || io.in.last
  
  val test = Counter(0, 784)
  assert(!loadEn || io.in.valid, message="Load valid", severity=FAILURE)
  assert(!io.out.last || (test.value === (((iShape._1 - 1) * (iShape._2 - 1)) - 1)), message="Write all")

  val fsm = new StateMachine {        
    io.in.ready := False
    io.out.valid := False
    io.out.last := False
    loadEn := False

    val reset : State = new State with EntryPoint {
      whenIsActive {
        loadEn := False
        for (channel <- 0 until iShape._3) {
          buffer(channel) := 0
          io.out.fragment(channel) := B"0000"
        }
        test := 0
        io.in.ready := False
        onEntry(colCounter.clear())
        onEntry(rowCounter.clear())
        goto(init)
      }
    }

    val init : State = new State {
      onEntry(colCounter.clear())
      whenIsActive {
        io.in.ready := True
        io.out.valid := False
        when (io.in.valid) {
          loadEn := True
          colCounter.increment()
          when (colCounter.willOverflow) {
            colCounter.clear()
            goto(read)
          }
        }
      }
    }

    val read : State = new State {
      whenIsActive {
        io.in.ready := True
        io.out.valid := False
        when(io.in.valid) {
          loadEn := True
          goto(write)
        }
      }
    }

    val write : State = new State {
      whenIsActive{
        io.in.ready := False
        io.out.valid := colCounter =/= 0
        loadEn := False
        when(io.out.ready) {
          when(io.out.valid) {test.increment()}
          when(colCounter.willOverflowIfInc) { //wenn Zeile geschrieben
            colCounter.clear()
            rowCounter.increment()
          }.otherwise{
            colCounter.increment()
          }
          when(colCounter.willOverflowIfInc && rowCounter.willOverflowIfInc) {
            when(wasInLast || io.in.last) { // if the last bit was set on input reset
              goto(reset)
            }.otherwise{ // otherwise drop rest of fragment
              goto(drop)
            }
            io.out.last := True
          }.otherwise{
            goto(read)
          }
        }
      }
    }

    val drop : State = new State {
      whenIsActive{
        io.in.ready := True
        when(io.in.valid && io.in.last) {
          goto(reset)
        }
      }
    }
  }

  when(loadEn) {
    for(channel <- 0 until iShape._3) {
      buffer(channel) := Cat(buffer(channel)(0, buffer(channel).getWidth - 1 bits), io.in.fragment(channel))
    }
  }

  for(channel <- 0 until iShape._3) {
    val channelBuf = buffer(channel)
    val high = channelBuf.getWidth - 1
    val low = channelBuf.getWidth - 2
    io.out.fragment(channel) := Cat(channelBuf(high downto low), channelBuf(1 downto 0))
  }

  // GenerationFlags.formal{
  //   when(initstate()) {
  //     assume(clockDomain.isResetActive && clockDomain.readClockWire)
  //   }.otherwise {
  //     assert(past(io.in.fire) || ((fillCounter - 1) === fillCounter))
  //   }
  // }

}
