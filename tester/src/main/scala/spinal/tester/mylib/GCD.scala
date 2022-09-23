/*
 * SpinalHDL
 * Copyright (c) Dolu, All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */

package mylib

import spinal.core._
import spinal.lib._
//import spinal.core.Formal._

import scala.util.Random

//Hardware definition
class GCD(dataWidth : Int) extends Component {
  val io = new Bundle {
    val a      = in  UInt(dataWidth bits)
    val b      = in  UInt(dataWidth bits)
    val en     = in  Bool
    val result = out UInt(dataWidth bits)
    val rdy    = out Bool
  }
  val reg_a = Reg(UInt(dataWidth bits)) init(0)
  val reg_b = Reg(UInt(dataWidth bits)) init(0)

  val rdy  = Reg(Bool) init(False)
  val calc = Reg(Bool) init(False)

  when(io.en){
    reg_a := io.a
    reg_b := io.b
    calc  := True
    rdy   := False
  }
  // calculation ongoing
  when (calc === True) {

    when (reg_a === reg_b) {
      rdy     := True
      calc    := False
    }
    .elsewhen (reg_b === 0) {
      reg_b := reg_a
    }
    .elsewhen (reg_a === 0) {
      reg_a := reg_b
    }
    .elsewhen (reg_a > reg_b) {
      reg_a := reg_b
      reg_b := reg_a
    }
    .elsewhen (reg_a < reg_b) {
      reg_b := reg_b - reg_a
    }
  }

  io.result := reg_b
  io.rdy    := rdy
/*  assert (
    assertion = !(io.en && calc),
    message   = "New input data while calculation is ongoing.",
    severity  = FAILURE
  )

  assert (!(io.en && io.rdy), "Assertion w/o severity")

  assert (!(io.en && io.rdy && calc))
 */
}
