// Cycle-level SDR SDRAM chip model for tb_system.sv: a SystemVerilog copy of
// the Chisel model in BurstSdramControllerSpec (CAS latency from the mode
// register, full-page sequential bursts, clock suspend, one open row per bank),
// with dataOnRising = true: the chip acts on the falling edge of the
// controller's clock (standing in for the forwarded, phase-shifted pin clock)
// and the read data is re-registered on the rising edge, so the controller's
// falling-edge capture (readCaptureFalling) sees it mid-window, as on the board.
// Storage is sparse (an associative array keyed by {bank, row, column}); unread
// words read 0.
`timescale 1ps/1ps

module sdram_model (
  input         clk,     // the controller's clock
  input         cke,
  input         cs,
  input         ras,
  input         cas,
  input         we,
  input  [1:0]  dqm,
  input  [1:0]  bank,
  input  [12:0] address,
  input  [15:0] data_from_controller,
  input         data_dir,
  output reg [15:0] data_to_controller
);
  logic [15:0] mem [int unsigned];

  reg        reg_cke = 1'b1;
  reg [2:0]  cas_latency = 3'd2;
  reg [12:0] open_row [0:3];
  reg [1:0]  burst_bank;
  reg [8:0]  burst_column;
  reg        read_active = 1'b0, write_active = 1'b0;
  reg [15:0] stage0, stage1, stage2, reg_dq;

  function automatic int unsigned key(input [1:0] b, input [12:0] r, input [8:0] c);
    key = {8'd0, b, r, c};
  endfunction
  function automatic [15:0] peek(input int unsigned k);
    peek = mem.exists(k) ? mem[k] : 16'h0000;
  endfunction

  wire [3:0] command = {cs, ras, cas, we};
  wire is_mode       = command == 4'b0000;
  wire is_precharge  = command == 4'b0010;
  wire is_active     = command == 4'b0011;
  wire is_write      = command == 4'b0100;
  wire is_read       = command == 4'b0101;
  wire is_burst_stop = command == 4'b0110;

  always @(negedge clk) begin
    reg_cke <= cke;
    if (reg_cke) begin
      stage1 <= stage0;
      stage2 <= stage1;
      reg_dq <= (cas_latency == 3'd3) ? stage1 : stage0;
      if (read_active) begin
        stage0 <= peek(key(burst_bank, open_row[burst_bank], burst_column));
        burst_column <= burst_column + 9'd1;
      end
      if (write_active && dqm == 2'b00) begin
        mem[key(burst_bank, open_row[burst_bank], burst_column)] = data_from_controller;
        burst_column <= burst_column + 9'd1;
      end
      if (is_mode) cas_latency <= address[6:4];
      if (is_active) open_row[bank] <= address;
      if (is_precharge || is_burst_stop) begin
        read_active <= 1'b0;
        write_active <= 1'b0;
      end
      if (is_read) begin
        read_active <= 1'b1;
        write_active <= 1'b0;
        burst_bank <= bank;
        burst_column <= address[8:0] + 9'd1;
        stage0 <= peek(key(bank, open_row[bank], address[8:0]));
      end
      if (is_write) begin
        read_active <= 1'b0;
        write_active <= 1'b1;
        burst_bank <= bank;
        burst_column <= address[8:0] + 9'd1;
        if (dqm == 2'b00) mem[key(bank, open_row[bank], address[8:0])] = data_from_controller;
      end
    end
  end

  always @(posedge clk) data_to_controller <= reg_dq;

  // Backdoor for the testbench: write a 16-bit word at a controller word address
  // {bank 2, row 13, column 9} (the controller's split of its 24-bit word address).
  task automatic poke_word(input [23:0] word_address, input [15:0] value);
    mem[{8'd0, word_address}] = value;
  endtask
endmodule
