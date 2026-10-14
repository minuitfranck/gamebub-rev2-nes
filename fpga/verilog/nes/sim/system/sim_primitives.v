// Behavioral stand-ins for the Xilinx primitives HandheldNes instantiates, for
// the xsim system testbench only (tb_system.sv). The clock generators take
// their clocks from the testbench, so that the system, gated core and SDRAM
// clocks change in the same simulation step with the same delta depth (a
// primitive model deriving one from the other would skew them by a delta and
// turn every crossing into a race).
`timescale 1ps/1ps

module BUFG (input I, output O);
  assign O = I;
endmodule

// The testbench computes the gated clock from the enable sampled before each
// rising edge (tb_system.clk_core); the output is that clock.
module BUFGCE #(parameter CE_TYPE = "SYNC", parameter IS_CE_INVERTED = 0, parameter IS_I_INVERTED = 0)
  (input I, input CE, output O);
  assign O = tb_system.clk_core;
endmodule

module MMCME2_BASE #(
  parameter real CLKFBOUT_MULT_F = 1.0, parameter real CLKIN1_PERIOD = 20.0,
  parameter real CLKOUT0_DIVIDE_F = 1.0, parameter real CLKOUT0_DUTY_CYCLE = 0.5, parameter real CLKOUT0_PHASE = 0.0,
  parameter integer CLKOUT1_DIVIDE = 1, parameter real CLKOUT1_DUTY_CYCLE = 0.5, parameter real CLKOUT1_PHASE = 0.0,
  parameter integer CLKOUT2_DIVIDE = 1, parameter real CLKOUT2_DUTY_CYCLE = 0.5, parameter real CLKOUT2_PHASE = 0.0,
  parameter integer DIVCLK_DIVIDE = 1
) (
  input CLKIN1, input CLKFBIN, input RST, input PWRDWN,
  output CLKOUT0, output CLKOUT0B, output CLKOUT1, output CLKOUT1B, output CLKOUT2, output CLKOUT2B,
  output CLKOUT3, output CLKOUT3B, output CLKOUT4, output CLKOUT5, output CLKOUT6,
  output CLKFBOUT, output CLKFBOUTB, output LOCKED
);
  assign CLKOUT0 = tb_system.clk_sys;
  assign CLKOUT1 = 1'b0;
  assign CLKOUT2 = 1'b0;
  assign {CLKOUT0B, CLKOUT1B, CLKOUT2B, CLKOUT3, CLKOUT3B, CLKOUT4, CLKOUT5, CLKOUT6, CLKFBOUT, CLKFBOUTB} = 10'd0;
  assign LOCKED = tb_system.locked;
endmodule

module PLLE2_BASE #(
  parameter integer CLKFBOUT_MULT = 1, parameter real CLKIN1_PERIOD = 10.0,
  parameter integer CLKOUT0_DIVIDE = 1, parameter real CLKOUT0_DUTY_CYCLE = 0.5, parameter real CLKOUT0_PHASE = 0.0,
  parameter integer CLKOUT1_DIVIDE = 1, parameter real CLKOUT1_DUTY_CYCLE = 0.5, parameter real CLKOUT1_PHASE = 0.0,
  parameter integer CLKOUT2_DIVIDE = 1, parameter real CLKOUT2_DUTY_CYCLE = 0.5, parameter real CLKOUT2_PHASE = 0.0,
  parameter integer DIVCLK_DIVIDE = 1
) (
  input CLKIN1, input CLKFBIN, input RST, input PWRDWN,
  output CLKOUT0, output CLKOUT1, output CLKOUT2, output CLKOUT3, output CLKOUT4, output CLKOUT5,
  output CLKFBOUT, output LOCKED
);
  assign CLKOUT0 = tb_system.clk_sys;
  assign CLKOUT1 = tb_system.clk_sdram;
  assign CLKOUT2 = tb_system.clk_sdram_pin;
  assign {CLKOUT3, CLKOUT4, CLKOUT5, CLKFBOUT} = 4'd0;
  assign LOCKED = tb_system.locked;
endmodule

// SAME_EDGE: D1 and D2 are taken at the rising edge; D1 drives Q for the high
// half, D2 for the low half.
module ODDR #(parameter DDR_CLK_EDGE = "SAME_EDGE", parameter INIT = 1'b0)
  (output Q, input C, input CE, input D1, input D2, input R, input S);
  reg q1 = INIT, q2 = INIT;
  always @(posedge C) begin
    if (S) begin q1 <= 1'b1; q2 <= 1'b1; end
    else if (R) begin q1 <= 1'b0; q2 <= 1'b0; end
    else if (CE) begin q1 <= D1; q2 <= D2; end
  end
  assign Q = C ? q1 : q2;
endmodule
