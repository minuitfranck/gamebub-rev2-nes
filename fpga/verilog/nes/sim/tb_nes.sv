// Game Bub NES port: xsim testbench for the vendored core (module NES) with
// ideal memories, a joypad and a frame dumper. Not part of any build.
//
// What it checks:
//  - the vendored RTL (after the Vivado rewrites) boots and renders a ROM;
//  - the capture rule of NesVideoCapture (a dot at pix_ce with cycle in
//    2..257 and scanline 0..239) yields a picture aligned to the frame;
//  - with +garbage=1, the stall assumption of the glue: external memory data
//    is only valid at the edges flagged by gb_cpu_latch / gb_ppu_latch (at
//    every other edge the testbench drives 0xA5), and the frames must match
//    a run with +garbage=0.
//
// Plusargs (no '=': xsim.bat splits arguments at '='): +flags<hex> +prgmask<hex>
// +chrmask<hex> (from make_rom_hex.py), +frames<n> (default 60),
// +press_start<frame> (press Start for 10 frames from there, 0 = never),
// +press_a<frame> (press A for 10 frames), +garbage<0|1>, +dump_from<frame>
// +dump_every<n> (frames written as frame_NNNN.hex: 240 lines of 256
// three-digit hex values, emphasis << 6 | color).
`timescale 1ns/1ps

module tb_nes;
  reg clk = 1'b0;
  always #23.28 clk = ~clk; // 21.477 MHz

  reg [63:0] flags;
  reg [9:0] prgmask, chrmask;
  integer frames_to_run, start_frame, a_frame, garbage, dump_from, dump_every;

  reg [7:0] prg     [0:(1<<22)-1];
  reg [7:0] chr     [0:(1<<20)-1];
  reg [7:0] wram    [0:2047];
  reg [7:0] cartram [0:32767];
  reg [7:0] ciram   [0:2047];
  reg [7:0] highram [0:32767];

  reg reset_nes = 1'b1;
  reg cold_reset = 1'b1;
  integer i;

  wire [24:0] cpumem_addr;
  wire        cpumem_read, cpumem_write;
  wire  [7:0] cpumem_dout;
  wire [21:0] ppumem_addr;
  wire        ppumem_read, ppumem_write;
  wire  [7:0] ppumem_dout;
  wire  [8:0] cycle, scanline;
  wire  [5:0] color;
  wire  [2:0] emphasis;
  wire  [1:0] nes_div;
  wire  [2:0] joypad_out;
  wire  [1:0] joypad_clock;
  wire        gb_cpu_latch, gb_ppu_latch;
  wire [15:0] sample;
  wire        corepaused;

  reg  [7:0] cpu_q = 8'h00;
  reg  [7:0] ppu_q = 8'h00;
  wire [7:0] cpumem_din = (garbage != 0 && !gb_cpu_latch) ? 8'hA5 : cpu_q;
  wire [7:0] ppumem_din = (garbage != 0 && !gb_ppu_latch) ? 8'hA5 : ppu_q;

  // Joypad (NES.sv joypad_bits): {Right, Left, Down, Up, Start, Select, B, A}
  reg [23:0] bits1 = 0, bits2 = 0;
  reg  [1:0] last_clock = 0;
  reg        press_start = 0, press_a = 0;
  always @(posedge clk) begin
    if (joypad_out[0]) begin
      bits1 <= {16'hFFFF, 4'b0000, press_start, 1'b0, 1'b0, press_a};
      bits2 <= {16'hFFFF, 8'h00};
    end
    if (!joypad_clock[0] && last_clock[0]) bits1 <= {1'b0, bits1[23:1]};
    if (!joypad_clock[1] && last_clock[1]) bits2 <= {1'b0, bits2[23:1]};
    last_clock <= joypad_clock;
  end

  NES nes (
    .clk(clk), .reset_nes(reset_nes), .ppu_rst_behavior(1'b0), .cold_reset(cold_reset),
    .pausecore(1'b0), .corepaused(corepaused), .sys_type(2'd0), .vs_dip_switches(8'd0),
    .nes_div(nes_div), .mapper_flags(reset_nes ? 64'd0 : flags), .sample(sample), .color(color),
    .joypad_clock(joypad_clock), .joypad_out(joypad_out),
    .joypad1_data({4'b0000, bits1[0]}), .joypad2_data({4'b0000, bits2[0]}),
    .fds_busy(1'b0), .fds_eject(1'b0), .fds_auto_eject(1'b0), .max_diskside(2'd0), .fds_fast(1'b0), .diskside(),
    .audio_channels(5'b11111), .ex_sprites(1'b0), .mask(2'd0), .dejitter_timing(1'b0),
    .cpumem_addr(cpumem_addr), .cpumem_read(cpumem_read), .cpumem_write(cpumem_write),
    .cpumem_dout(cpumem_dout), .cpumem_din(cpumem_din),
    .ppumem_addr(ppumem_addr), .ppumem_read(ppumem_read), .ppumem_write(ppumem_write),
    .ppumem_dout(ppumem_dout), .ppumem_din(ppumem_din), .refresh(),
    .prg_mask({11'd0, prgmask}), .chr_mask({10'd0, chrmask}),
    .bram_addr(), .bram_din(8'd0), .bram_dout(), .bram_write(), .bram_override(),
    .cycle(cycle), .scanline(scanline), .int_audio(1'b1), .ext_audio(1'b1), .apu_ce(),
    .gg(1'b1), .gg_code(129'd0), .gg_avail(), .gg_reset(1'b0), .emphasis(emphasis),
    .save_written(), .mapper_has_flashsaves(), .debug_dots(1'b0),
    .mapper_has_savestate(), .increaseSSHeaderCount(1'b1), .save_state(1'b0), .load_state(1'b0),
    .savestate_number(2'd0), .sleep_savestate(), .state_loaded(),
    .hsync(), .hblank(), .vsync(), .vblank(), .composite_a(), .composite_b(),
    .Savestate_SDRAMAddr(), .Savestate_SDRAMRdEn(), .Savestate_SDRAMWrEn(), .Savestate_SDRAMWriteData(),
    .Savestate_SDRAMReadData(8'd0),
    .SaveStateExt_Din(), .SaveStateExt_Adr(), .SaveStateExt_wren(), .SaveStateExt_rst(),
    .SaveStateExt_Dout(64'd0), .SaveStateExt_load(),
    .SAVE_out_Din(), .SAVE_out_Dout(64'd0), .SAVE_out_Adr(), .SAVE_out_rnw(), .SAVE_out_ena(), .SAVE_out_be(),
    .SAVE_out_done(1'b0),
    .gb_cpu_latch(gb_cpu_latch), .gb_ppu_latch(gb_ppu_latch)
  );

  // Ideal memories, decoded like HandheldNes: data one clock after the address.
  wire cpu_prg  = cpumem_addr[24:21] == 4'd0;
  wire cpu_wram = cpumem_addr[24:11] == (25'h380000 >> 11);
  wire cpu_cart = cpumem_addr[24:15] == (25'h3C0000 >> 15);
  wire ppu_chr  = ppumem_addr[21:20] == 2'b10;
  wire ppu_high = ppumem_addr[21:15] == (22'h3F8000 >> 15);
  wire ppu_nt   = ppumem_addr[21:11] == (22'h3A0000 >> 11);
  integer cpu_reads = 0, ppu_reads = 0;
  reg last_cpu_read = 0, last_ppu_read = 0;
  always @(posedge clk) begin
    last_cpu_read <= cpumem_read;
    last_ppu_read <= ppumem_read;
    if (cpumem_read && !last_cpu_read) cpu_reads = cpu_reads + 1;
    if (ppumem_read && !last_ppu_read) ppu_reads = ppu_reads + 1;
    cpu_q <= cpu_prg ? prg[cpumem_addr[21:0]] : cpu_wram ? wram[cpumem_addr[10:0]] :
             cpu_cart ? cartram[cpumem_addr[14:0]] : 8'hFF;
    if (cpumem_write) begin
      if (cpu_wram) wram[cpumem_addr[10:0]] <= cpumem_dout;
      if (cpu_cart) cartram[cpumem_addr[14:0]] <= cpumem_dout;
    end
    ppu_q <= ppu_chr ? chr[ppumem_addr[19:0]] : ppu_high ? highram[ppumem_addr[14:0]] :
             ppu_nt ? ciram[ppumem_addr[10:0]] : 8'hFF;
    if (ppumem_write) begin
      if (ppu_chr) chr[ppumem_addr[19:0]] <= ppumem_dout;
      if (ppu_high) highram[ppumem_addr[14:0]] <= ppumem_dout;
      if (ppu_nt) ciram[ppumem_addr[10:0]] <= ppumem_dout;
    end
  end

  // Capture, as NesVideoCapture.
  reg pix_ce = 0;
  reg [8:0] frame_buf [0:61439];
  integer frame = 0, dots = 0, fd, x, y;
  reg last_vblank = 1;
  reg [8*64-1:0] name;
  always @(posedge clk) begin
    pix_ce <= (nes_div == 2'd0);
    if (pix_ce && !corepaused) begin
      if (cycle >= 2 && cycle <= 257 && scanline < 240) begin
        frame_buf[scanline * 256 + (cycle - 2)] <= {emphasis, color};
        dots = dots + 1;
      end
      if (last_vblank && scanline < 240) last_vblank <= 0;
      if (!last_vblank && scanline >= 240) begin
        last_vblank <= 1;
        if (frame >= dump_from && dump_every > 0 && ((frame - dump_from) % dump_every) == 0) begin
          $sformat(name, "frame_%04d.hex", frame);
          fd = $fopen(name, "w");
          for (y = 0; y < 240; y = y + 1) begin
            for (x = 0; x < 256; x = x + 1) $fwrite(fd, "%03x ", frame_buf[y * 256 + x]);
            $fwrite(fd, "\n");
          end
          $fclose(fd);
        end
        $display("frame %0d done at %0t: %0d dots, cpu reads %0d, ppu reads %0d, sample %04x", frame, $time, dots, cpu_reads, ppu_reads, sample);
        dots = 0;
        frame = frame + 1;
        press_start <= (start_frame != 0 && frame >= start_frame && frame < start_frame + 10);
        press_a <= (a_frame != 0 && frame >= a_frame && frame < a_frame + 10);
        if (frame >= frames_to_run) $finish;
      end
    end
  end

  // +xcheck<n>: report which NES outputs are unknown (X) every 997 clocks for n
  // clocks after the reset is released, then stop (xsim starts undriven
  // registers at X; the FPGA at 0).
  integer xcheck = 0, xcount = 0;
  always @(posedge clk) begin
    if (xcheck != 0 && !reset_nes) begin
      xcount = xcount + 1;
      if (xcount % 997 == 0)
        $display("xcheck %0d: cpumem_read %b write %b addr %b | ppumem_read %b write %b addr %b | nes_div %b cycle %b color %b latches %b%b",
          xcount, cpumem_read, cpumem_write, $isunknown(cpumem_addr), ppumem_read, ppumem_write, $isunknown(ppumem_addr),
          nes_div, $isunknown(cycle), $isunknown(color), gb_cpu_latch, gb_ppu_latch);
      if (xcount % 997 == 0)
        $display("  ppu: vram_r_ppudata %b is_rendering_d %b re_sr %b read_cycle %b special_dot %b evenframe %b scanline %0d cycle %0d reset %b",
          nes.ppu.vram_r_ppudata, nes.ppu.is_rendering_d, nes.ppu.re_sr, nes.ppu.read_cycle, nes.ppu.special_dot,
          nes.ppu.evenframe, nes.ppu.scanline, nes.ppu.cycle, nes.ppu.reset);
      if (xcount >= xcheck) $finish;
    end
  end

  initial begin
    if (!$value$plusargs("xcheck%d", xcheck)) xcheck = 0;
    if (!$value$plusargs("flags%h", flags)) flags = 64'd0;
    if (!$value$plusargs("prgmask%h", prgmask)) prgmask = 10'h7;
    if (!$value$plusargs("chrmask%h", chrmask)) chrmask = 10'h3;
    if (!$value$plusargs("frames%d", frames_to_run)) frames_to_run = 60;
    if (!$value$plusargs("press_start%d", start_frame)) start_frame = 0;
    if (!$value$plusargs("press_a%d", a_frame)) a_frame = 0;
    if (!$value$plusargs("garbage%d", garbage)) garbage = 0;
    if (!$value$plusargs("dump_from%d", dump_from)) dump_from = 0;
    if (!$value$plusargs("dump_every%d", dump_every)) dump_every = 0;
    for (i = 0; i < (1<<22); i = i + 1) prg[i] = 8'h00;
    for (i = 0; i < (1<<20); i = i + 1) chr[i] = 8'h00;
    for (i = 0; i < 2048; i = i + 1) begin wram[i] = 8'h00; ciram[i] = 8'h00; end
    for (i = 0; i < 32768; i = i + 1) begin cartram[i] = 8'h00; highram[i] = 8'h00; end
    $readmemh("prg.hex", prg);
    $readmemh("chr.hex", chr);
    $display("flags=%h prgmask=%h chrmask=%h garbage=%0d", flags, prgmask, chrmask, garbage);
    repeat (40000) @(posedge clk);
    cold_reset = 0;
    repeat (1000) @(posedge clk);
    reset_nes = 0;
  end
endmodule
