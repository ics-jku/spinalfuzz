package spinal.sim.fuzz


  // check for ongoing afl-run
  // check for new testcases available
  // do lcov over testcases

object FuzzManager{
  var cpuAffinity = 0
  lazy val cpuCount = {
    try {
      val systemInfo = new oshi.SystemInfo
      systemInfo.getHardware.getProcessor.getLogicalProcessorCount
    } catch {
      // fallback when oshi can't work on Apple M1
      // see https://github.com/oshi/oshi/issues/1462
      // remove this workaround when the issue is fixed
      case e @ (_ : NoClassDefFoundError | _ : UnsatisfiedLinkError) => Runtime.getRuntime().availableProcessors()
    }
  }

  def newCpuAffinity() : Int = synchronized {
    val ret = cpuAffinity
    cpuAffinity = (cpuAffinity + 1) % cpuCount
    ret
  }
}


class FuzzManager () { 

  var userData : Any = null

  def run(body : => Unit): Unit ={
    val startAt = System.nanoTime()
/*    def threadBody(body : => Unit) : Unit = {
      body
      throw new SimSuccess
    }
    val tRoot = new SimThread(threadBody(body))
    schedule(delay=0, tRoot)
 */
    runWhile(true)

    
    val endAt = System.nanoTime()
    val duration = (endAt - startAt)*1e-9
    println(f"""[Done] Fuzzing done in ${duration*1e3}%1.3f ms""")
  }

  def runWhile(continueWhile : => Boolean = true): Unit ={


  }

}

