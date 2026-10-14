// Compiled first by run_system.sh: Chisel's generated registers and memories
// start at 0, as they do in the FPGA (without this they start at X in xsim
// and the X spreads through the stall logic into the gated clock).
`define RANDOMIZE_REG_INIT
`define RANDOMIZE_MEM_INIT
`define RANDOM 32'h0
