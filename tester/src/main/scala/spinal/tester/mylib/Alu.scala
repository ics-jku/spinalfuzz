package mylib

import spinal.core._

import spinal.lib.cpu.riscv.impl._
import spinal.lib.cpu.riscv.impl.Utils._

// simple implementation that adds Registers to all in- and outputs, to test alu implementation (issues because of missing reset and clock)

class AluInst extends Component{
  val io = new Bundle{
    val func = in(ALU)
    val doSub = in Bool
    val src0 = in Bits(32 bit)
    val src1 = in Bits(32 bit)
    val result = out Bits(32 bit)
    val adder = out UInt(32 bit)
  }



  val func   = Reg(ALU)
  val doSub  = Reg(Bool)
  val src0   = Reg(Bits(32 bit))
  val src1   = Reg(Bits(32 bit))
  val result = Reg(Bits(32 bit))
  val adder  = Reg(UInt(32 bit))


  func      := io.func
  doSub     := io.doSub
  src0      := io.src0
  src1      := io.src1
  io.result := result
  io.adder  := adder


  val alu = new Alu

  alu.io.func  := func
  alu.io.doSub := doSub
  alu.io.src0  := src0
  alu.io.src1  := src1
  result       := alu.io.result
  adder        := alu.io.adder

}
