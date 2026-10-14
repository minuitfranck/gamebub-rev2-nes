// Behavioral replacements for the SDRAM path of HandheldNes in the xsim system
// testbench: PipelineMemoryBurstCdc becomes a pipelined memory on the system
// clock with a configurable data-phase latency (so the ROM cache's misses stall
// the core as they do on the board), and BurstSdramController an idle stub.
// Both run on the system clock only, which keeps the simulation free of
// delta-cycle races between the 1x and 2x clocks. The real CDC and controller
// are the SNES port's, proven on rev2 hardware.
`timescale 1ps/1ps

module PipelineMemoryBurstCdc (
  input         clock,
  input         reset,
  input         io_slowClock,
  output        io_initiator_ready,
  input         io_initiator_enable,
  input  [24:0] io_initiator_address,
  input         io_initiator_isWrite,
  output [31:0] io_initiator_dataRead,
  input  [31:0] io_initiator_dataWrite,
  input         io_target_ready,
  output        io_target_enable,
  output [24:0] io_target_address,
  output        io_target_isWrite,
  input  [31:0] io_target_dataRead,
  output [31:0] io_target_dataWrite
);
  logic [31:0] mem [int unsigned];
  reg        data_phase = 1'b0;
  reg [22:0] dp_word;
  reg        dp_write;
  integer    wait_count = 0;
  integer    latency;
  integer    reads = 0, writes = 0;
  initial if (!$value$plusargs("sdram_latency%d", latency)) latency = 4;

  assign io_initiator_ready = !data_phase || wait_count == 0;
  assign io_initiator_dataRead = mem.exists(dp_word) ? mem[dp_word] : 32'h0;
  assign io_target_enable = 1'b0;
  assign io_target_address = 25'd0;
  assign io_target_isWrite = 1'b0;
  assign io_target_dataWrite = 32'd0;

  always @(posedge io_slowClock) begin
    if (data_phase) begin
      if (wait_count > 0) begin
        wait_count <= wait_count - 1;
      end else begin
        if (dp_write) begin
          mem[dp_word] = io_initiator_dataWrite;
          writes = writes + 1;
        end else begin
          reads = reads + 1;
        end
        data_phase <= 1'b0;
      end
    end
    if (io_initiator_ready && io_initiator_enable) begin
      data_phase <= 1'b1;
      dp_word <= io_initiator_address[24:2];
      dp_write <= io_initiator_isWrite;
      wait_count <= io_initiator_isWrite ? 0 : latency;
    end
  end

  function automatic [31:0] peek(input [24:0] byte_address);
    peek = mem.exists(byte_address[24:2]) ? mem[byte_address[24:2]] : 32'h0;
  endfunction
endmodule

module BurstSdramController (
  input         clock,
  input         reset,
  output        io_signals_cke,
  output        io_signals_cs,
  output        io_signals_ras,
  output        io_signals_cas,
  output        io_signals_we,
  output [1:0]  io_signals_dqm,
  output [1:0]  io_signals_bank,
  output [12:0] io_signals_address,
  input  [15:0] io_signals_dataIn,
  output [15:0] io_signals_dataOut,
  output        io_signals_dataDir,
  output        io_mem_ready,
  input         io_mem_enable,
  input  [24:0] io_mem_address,
  input         io_mem_isWrite,
  output [31:0] io_mem_dataRead,
  input  [31:0] io_mem_dataWrite
);
  assign {io_signals_cke, io_signals_cs, io_signals_ras, io_signals_cas, io_signals_we} = 5'b01111;
  assign io_signals_dqm = 2'b11;
  assign io_signals_bank = 2'b00;
  assign io_signals_address = 13'd0;
  assign io_signals_dataOut = 16'd0;
  assign io_signals_dataDir = 1'b0;
  assign io_mem_ready = 1'b0;
  assign io_mem_dataRead = 32'd0;
endmodule
