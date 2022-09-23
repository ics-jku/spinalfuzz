# About SpinalFuzz
This repository branched from [SpinalHDL Repository](https://github.com/SpinalHDL/SpinalHDL).
Some changes are made in `core` and `sim` to add fuzzing to the workflow.
Testcases were reduced to SpinalFuzz relevant Benchmarks.
SpinalFuzz is only available on Linux systems and require bash to work. 

## Publication
The initial paper on SpinalFuzz was presented at ETS'22 and can be downloaded here: https://www.ics.jku.at/files/2022ETS_SpinalFuzz.pdf
and can be cited as follows:
```bibtex
@inproceedings{RG:2022,
  author =        {Katharina Ruep and Daniel Gro{\ss}e},
  booktitle =     {European Test Symposium},
  pages =         {1--4},
  title =         {{SpinalFuzz}: Coverage-Guided Fuzzing for {SpinalHDL} Designs},
  year =          {2022},
}
```

## Getting Started
To run SpinalFuzz the following is required:
1) clone this repository
	```sh
	git clone git@github.com:ics-jku/spinalfuzz.git
	```
2) get SpinalHDL requirements
	A detailed description can be found https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Getting%20Started/getting_started.html#requirements-things-to-download-to-get-started .
	
3) get Verilator
	```sh
	sudo apt-get install git make autoconf g++ flex bison  # First time prerequisites
	git clone http://git.veripool.org/git/verilator   # Only first time
	unset VERILATOR_ROOT  # For bash
	cd verilator
	git pull        # Make sure we're up-to-date
	git checkout v4.217
	autoconf        # Create ./configure script
	./configure
	make -j$(nproc)
	sudo make install
	echo "DONE"
	```
	For more versions and information about Verilator, see https://www.veripool.org/verilator/ and github https://github.com/verilator/verilator .
4) get AFLplusplus
	```sh
	sudo apt-get update
	sudo apt-get install -y build-essential python3-dev automake cmake git flex bison libglib2.0-dev libpixman-1-dev python3-setuptools cargo libgtk-3-dev
	sudo apt-get install -y lld-12 llvm-12 llvm-12-dev clang-12
	sudo apt-get install -y gcc-$(gcc --version|head -n1|sed 's/\..*//'|sed 's/.* //')-plugin-dev libstdc++-$(gcc --version|head -n1|sed 's/\..*//'|sed 's/.* //')-dev
	sudo apt-get install -y ninja-build # for QEMU mode
	git clone https://github.com/AFLplusplus/AFLplusplus
	cd AFLplusplus
	make source-only
	sudo make install
	```
	For more versions and information about AFL++, see https://aflplus.plus/ and github https://github.com/AFLplusplus/AFLplusplus .

## How to use

1. start SBT
   ```
   sbt
	```
2. enter tester
	```sbt
	project tester
	```
3. show all possible Testcases/Main Classes [optional]
   ```sbt
   show discoveredMainClasses
   ```
4. start with benchmark settings
   ```sbt
   runMain mylib.<setting>
   ```
   `<setting>` is composed of:
	- `<benchmark>Fuzz`:	  Fuzz run
	- `<benchmark>Sim`:	  Random simulation run
    - `<benchmark>Verilog`: Verilog code generation only
	
## Benchmarks
- GCD
- CNN-Buffer (CnnBuffer in SpinalHDL)
- Alu
- I2cSlave
- Apb3Timer
- SpiXdrMaster
- Apb3SpiSlave
- UartCtrl
- Apb3UartCtrl
- BmbI2cCtrl

The files of code are placed in `spinalfuzz/tester/src/main/scala/spinal/tester/mylib` and are named `<benchmark>Test.scala`. For some benchmarks additional files with name `<benchmark>.scala` are needed too, especially when the benchmarks are not part of the SpinalHDL library.
	

## About SpinalHDL Links

 - Documentation                   https://spinalhdl.github.io/SpinalDoc-RTD/
 - Presentation of the language    https://spinalhdl.github.io/SpinalDoc-RTD/SpinalHDL/Getting%20Started/presentation.html
 - SBT base project                https://github.com/SpinalHDL/SpinalTemplateSbt
 - Gradle base project             https://github.com/SpinalHDL/SpinalTemplateGradle
 - Jupyter bootcamp                https://github.com/SpinalHDL/Spinal-bootcamp
 - Workshop                        https://github.com/SpinalHDL/SpinalWorkshop
 - Google group                    https://groups.google.com/forum/#!forum/spinalhdl-hardware-description-language


### SpinalHDL License

The SpinalHDL core is using the LGPL3 license while SpinalHDL lib is using the MIT license. That's for the formalities. But there are some practical statements implied by those licenses:

Your freedoms are:

 - You can use SpinalHDL core and lib in your closed/commercial projects.
 - The generated RTL is yours (.vhd/.v files)
 - Your hardware description is yours (.scala files)

Your obligations (and my wish) are:

 - If you modify the SpinalHDL core (the compiler itself), please, share your improvements.

Also, SpinalHDL is provided "as is", without warranty of any kind.
