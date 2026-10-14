// Game Bub NES port: xsim system testbench for HandheldNes (the Chisel glue as
// generated, the vendored NES core, an async SRAM model, the SDRAM path stub
// of sdram_path_stub.sv and the primitive stand-ins of sim_primitives.v).
//
// It drives the host side the way the firmware does (file transfers into the
// memory windows, the framework commands), then runs the game and captures
// the video output the way HandheldTop's framebuffer writer does.
//
// Plusargs (no '=': xsim.bat splits arguments at '='):
//   +file_size<n>        size of the .nes file (file_words.hex holds its 32-bit LE words)
//   +frontdoor_words<n>  words written through the host window (the rest is loaded
//                        directly into the SDRAM stub / SRAM model as the splitter would);
//                        default: the whole file
//   +frames<n>           frames to run after CoreRun (default 10)
//   +press_start<frame>  hold Start for 10 frames from this frame (0 = never)
//   +dump_from<frame> +dump_every<n>   write sysframe_NNNN.hex (RGB555 hex)
//   +savestate<frame>    at this frame: Save State (slot 1), run +load_after frames,
//                        Load State, and report the status bits (0 = no save-state test)
//   +load_after<n>       frames between the save and the load (default 5)
//   +sdram_latency<n>    data-phase latency of the SDRAM stub (default 4)
//   +clearx2             (preferred) hold the core's PPU read / write and CPU write strobes at 0 from
//                        the start until 20000 clocks after setup (the core still in reset), so
//                        that X from xsim's uninitialized core state never reaches the glue
//   +clearx1             before CoreRun, hold the core in reset 20000 more clocks, then zero the
//                        CHR / save-state SRAM bridges' read counters once they are idle: xsim
//                        starts the core's registers at X (the FPGA at 0) and a read issued
//                        from X during the cold reset leaves those counters X for good
//   +longreset<n>        hold the glue's reset for n clocks at the start (default 20), with the
//                        core clock running: flushes the core's initial X before the glue sees it
//   +savtest1            transfer a patterned 8 KiB .sav (file id 1) before setup, read the
//                        save back after the run (FILE_READ_START 1 size) and compare
`timescale 1ps/1ps

module tb_system;
  reg clk_sys = 1'b0, clk_sdram = 1'b0, clk_core = 1'b0, clk_sdram_pin = 1'b0;
  reg locked = 1'b0;
  reg reset = 1'b1;

  // One process makes every clock edge, the gated core clock from the enable
  // sampled before the edge (BUFGCE).
  // +xrun1: an unknown coreRun delivers the edge (X from xsim's uninitialized core
  // state would otherwise stop the gated clock for good; see +clearx2).
  integer xrun = 0;
  initial begin
    if (!$value$plusargs("xrun%d", xrun)) xrun = 0;
    forever begin
      #11640;
      clk_sdram = 1'b1;
      clk_core = (xrun != 0) ? (dut.coreRun !== 1'b0) : dut.coreRun;
      clk_sys = 1'b1;
      #11640;
      clk_sdram = 1'b0;
      #11640;
      clk_sdram = 1'b1;
      #11640;
      clk_sdram = 1'b0;
      clk_sys = 1'b0;
      clk_core = 1'b0;
    end
  end

  // Host interface
  reg         host_enable = 1'b0, host_write = 1'b0, cmd_request = 1'b0;
  reg  [31:0] host_address = 32'd0, host_data = 32'd0;
  wire        host_done, cmd_busy, cmd_done, cmd_error;
  wire [31:0] host_read_data;
  reg  [11:0] buttons = 12'd0; // a b x y up down left right l r start select

  // SRAM (512 KiB, 16-bit words), asynchronous
  wire        sram_weN, sram_oeN, sram_dataDir;
  wire  [1:0] sram_writeMaskN;
  wire [17:0] sram_address;
  wire [15:0] sram_dataOut;
  reg  [15:0] sram [0:262143];
  wire [15:0] sram_dataIn = sram[sram_address];
  always @(*) begin
    if (!sram_weN) begin
      if (!sram_writeMaskN[0]) sram[sram_address][7:0] = sram_dataOut[7:0];
      if (!sram_writeMaskN[1]) sram[sram_address][15:8] = sram_dataOut[15:8];
    end
  end

  wire  [4:0] video_r, video_g, video_b;
  wire        video_de, video_vblank, video_hblank;
  wire [15:0] audio_left, audio_right;

  HandheldNes dut (
    .clock(clk_sys), .reset(reset), .io_clocks_clockIn50M(1'b0),
    .io_clocks_clockOutSystem(), .io_clocks_clockOutDisplay(), .io_clocks_clockOutSpi(), .io_clocks_locked(),
    .io_video_data_r(video_r), .io_video_data_g(video_g), .io_video_data_b(video_b),
    .io_video_dataEnable(video_de), .io_video_vblank(video_vblank), .io_video_hblank(video_hblank),
    .io_audio_left(audio_left), .io_audio_right(audio_right),
    .io_host_mem_enable(host_enable), .io_host_mem_write(host_write), .io_host_mem_done(host_done),
    .io_host_mem_address(host_address), .io_host_mem_dataRead(host_read_data), .io_host_mem_dataWrite(host_data),
    .io_host_commandHost_request(cmd_request), .io_host_commandHost_busy(cmd_busy),
    .io_host_commandHost_done(cmd_done), .io_host_commandHost_error(cmd_error),
    .io_input_buttons_a(buttons[0]), .io_input_buttons_b(buttons[1]), .io_input_buttons_x(buttons[2]),
    .io_input_buttons_y(buttons[3]), .io_input_buttons_up(buttons[4]), .io_input_buttons_down(buttons[5]),
    .io_input_buttons_left(buttons[6]), .io_input_buttons_right(buttons[7]), .io_input_buttons_l(buttons[8]),
    .io_input_buttons_r(buttons[9]), .io_input_buttons_start(buttons[10]), .io_input_buttons_select(buttons[11]),
    .io_sram_weN(sram_weN), .io_sram_oeN(sram_oeN), .io_sram_writeMaskN(sram_writeMaskN),
    .io_sram_address(sram_address), .io_sram_dataIn(sram_dataIn), .io_sram_dataOut(sram_dataOut),
    .io_sram_dataDir(sram_dataDir),
    .io_sdram_clock(), .io_sdram_cke(), .io_sdram_cs(), .io_sdram_ras(), .io_sdram_cas(), .io_sdram_we(),
    .io_sdram_dqm(), .io_sdram_bank(), .io_sdram_address(), .io_sdram_dataIn(16'd0), .io_sdram_dataOut(),
    .io_sdram_dataDir()
  );

  task automatic tick();
    @(posedge clk_sys);
    #1000;
  endtask

  task automatic host_access(input [31:0] address, input write, input [31:0] data, output [31:0] result);
    integer n;
    host_enable = 1'b1; host_write = write; host_address = address; host_data = data;
    n = 0;
    forever begin
      tick();
      n = n + 1;
      if (host_done) break;
      if (n > 200000) begin $display("HOST TIMEOUT at 0x%08x", address); $finish; end
    end
    result = host_read_data;
    tick();
    host_enable = 1'b0;
  endtask

  task automatic wr(input [31:0] address, input [31:0] data);
    reg [31:0] unused;
    host_access(address, 1'b1, data, unused);
  endtask

  task automatic rd(input [31:0] address, output [31:0] data);
    host_access(address, 1'b0, 32'd0, data);
  endtask

  task automatic command(input [15:0] cmd, input [31:0] arg1, input [31:0] arg2, output [31:0] result, output error);
    integer n;
    wr(32'hF0000000, cmd); wr(32'hF0000004, arg1); wr(32'hF0000008, arg2); wr(32'hF000000C, 32'd0);
    cmd_request = 1'b1;
    n = 0;
    forever begin
      tick();
      n = n + 1;
      if (cmd_done || cmd_error) break;
      if (n > 1000000) begin $display("COMMAND TIMEOUT %04x", cmd); $finish; end
    end
    error = cmd_error;
    cmd_request = 1'b0;
    tick(); tick();
    rd(32'hF0000000, result);
  endtask

  // Framebuffer writer (HandheldTop): x on dataEnable, row on an hblank rising edge, frame on vblank.
  reg [14:0] fb [0:61439];
  integer fb_x = 0, fb_y = 0, frame = 0, frames_to_run, press_start, dump_from, dump_every, fd, x, y, pixels = 0;
  reg last_hblank = 1'b0, last_vblank = 1'b0;
  reg [8*64-1:0] name;
  reg running = 1'b0;
  always @(posedge clk_sys) begin
    last_hblank <= video_hblank;
    last_vblank <= video_vblank;
    if (video_vblank) begin
      fb_x = 0; fb_y = 0;
    end else if (video_hblank) begin
      if (!last_hblank) begin fb_x = 0; fb_y = fb_y + 1; end
    end else if (video_de) begin
      if (fb_y < 240 && fb_x < 256) fb[fb_y * 256 + fb_x] <= {video_r, video_g, video_b};
      fb_x = fb_x + 1;
      pixels = pixels + 1;
    end
    if (video_vblank && !last_vblank && running) begin
      if (dump_every > 0 && frame >= dump_from && ((frame - dump_from) % dump_every) == 0) begin
        $sformat(name, "sysframe_%04d.hex", frame);
        fd = $fopen(name, "w");
        for (y = 0; y < 240; y = y + 1) begin
          for (x = 0; x < 256; x = x + 1) $fwrite(fd, "%04x ", fb[y * 256 + x]);
          $fwrite(fd, "\n");
        end
        $fclose(fd);
      end
      $display("frame %0d at %0t ps: %0d pixels, rows %0d, run %0d stall %0d (prg %0d chr %0d), rom req %0d hits %0d misses %0d, chr reads %0d",
        frame, $time, pixels, fb_y, dut.statRunCycles, dut.statStallCycles, dut.statStallsPrg, dut.statStallsChr,
        dut.statRomRequests, dut.statRomBufferHits, dut.statRomCacheMisses, dut.statChrReads);
      $display("  debug: coreRun %b stallPrg %b romPending %b chrPending %b chrFull %b ssPending %b ppuLatch %b regCoreReset %b coldReset %b warm %0d focus %b romValid %b",
        dut.coreRun, dut.stallPrg, dut.romPending, dut._chrPort_io_pendingRead, dut._chrPort_io_full, dut._ssSramPort_io_pending,
        dut._core_gb_ppu_latch, dut.regCoreReset, dut.coldReset, dut.warmResetCounter, dut.regCoreFocus, dut._romInfo_io_valid);
      pixels = 0;
      frame = frame + 1;
      buttons[10] <= (press_start != 0 && frame >= press_start && frame < press_start + 10);
    end
  end

  // +xprobe<n>: for n system clocks from the start, print which glue / core
  // handshake signals are unknown (X) whenever that set changes, then stop.
  integer xprobe = 0, xcycles = 0;
  reg [15:0] xset = 16'd0, xlast = 16'hFFFF;
  always @(posedge clk_sys) begin
    if (xprobe != 0) begin
      xcycles = xcycles + 1;
      xset = {$isunknown(dut.coreRun), $isunknown(dut.stallChr), $isunknown(dut._chrPort_io_pendingRead),
              $isunknown(dut._chrPort_io_full), $isunknown(dut._core_ppumem_read), $isunknown(dut._core_ppumem_write),
              $isunknown(dut._core_ppumem_addr), $isunknown(dut._core_cpumem_read), $isunknown(dut._core_cpumem_write),
              $isunknown(dut._core_cpumem_addr), $isunknown(dut._core_gb_ppu_latch), $isunknown(dut._core_gb_cpu_latch),
              $isunknown(dut.stallPrg), $isunknown(dut.romPending), $isunknown(dut._ssSramPort_io_pending),
              $isunknown(dut.coldReset)};
      if (xset !== xlast) begin
        $display("xprobe %0d (%0t ps) running %b: X in coreRun %b stallChr %b chrPendingRead %b chrFull %b ppu read %b write %b addr %b | cpu read %b write %b addr %b | latches ppu %b cpu %b | stallPrg %b romPending %b ssPending %b coldReset %b",
          xcycles, $time, running, xset[15], xset[14], xset[13], xset[12], xset[11], xset[10], xset[9], xset[8], xset[7], xset[6],
          xset[5], xset[4], xset[3], xset[2], xset[1], xset[0]);
        xlast = xset;
      end
      if (xcycles >= xprobe) $finish;
    end
  end

  // +sramx1: report the first clocks at which the SRAM path (arbiter inputs /
  // outputs, controller, SRAM data) holds X, with the context.
  integer sramx = 0, sramx_reports = 0, sramx_cycle = 0;
  always @(posedge clk_sys) begin
    sramx_cycle = sramx_cycle + 1;
    if (sramx != 0 && (sramx != 2 || running) && sramx_reports < 12 && ($isunknown(dut._sramHostAdapter_io_sram_enable) || $isunknown(dut._chrPort_io_mem_enable) ||
        $isunknown(dut._ssSramPort_io_mem_enable) || $isunknown(dut._sramArbiter_io_target_enable) ||
        $isunknown(dut._sramController_io_mem_done) || $isunknown(dut._chrPort_io_pendingRead))) begin
      sramx_reports = sramx_reports + 1;
      $display("sramx cycle %0d (%0t ps) reset %b running %b: X hostEn %b chrEn %b ssEn %b arbEn %b ctrlDone %b chrPendRead %b | chr ceN-in ppuSram %b ppu read %b write %b addr %06x | ss rd %b wr %b addr %07x | sram addr %05x dataIn %04x",
        sramx_cycle, $time, reset, running, $isunknown(dut._sramHostAdapter_io_sram_enable), $isunknown(dut._chrPort_io_mem_enable),
        $isunknown(dut._ssSramPort_io_mem_enable), $isunknown(dut._sramArbiter_io_target_enable), $isunknown(dut._sramController_io_mem_done),
        $isunknown(dut._chrPort_io_pendingRead), dut.ppuSram, dut._core_ppumem_read, dut._core_ppumem_write, dut._core_ppumem_addr,
        dut._core_Savestate_SDRAMRdEn, dut._core_Savestate_SDRAMWrEn, dut._core_Savestate_SDRAMAddr, sram_address, sram_dataIn);
    end
  end

  // +sramtrace<cycle>: for 220 clocks from that system clock, print the X status of the CHR bridge,
  // the SRAM arbiter and controller (debugging aid).
  integer sramtrace = 0;
  always @(posedge clk_sys) begin
    if (sramtrace != 0 && sramx_cycle >= sramtrace && sramx_cycle < sramtrace + 220)
      $display("sramtrace %0d: bridge ceN %b oeN %b weN %b addrX %b | rdReq %b wrReq %b enq %b deqV %b cnt %b busy %b issue %b done %b | arb busy %b en %b | ctrl state %b regDone %b done %b | sram din X %b",
        sramx_cycle, dut.chrPort.io_ceN, dut.chrPort.io_oeN, dut.chrPort.io_weN, $isunknown(dut.chrPort.io_address),
        dut.chrPort.readRequest, dut.chrPort.writeRequest, dut.chrPort.queue_io_enq_valid, dut.chrPort._queue_io_deq_valid,
        dut.chrPort._queue_io_count, dut.chrPort.busy, dut.chrPort.issue, dut.chrPort.done, dut.sramArbiter.busy,
        dut.sramArbiter.io_target_enable, dut.sramController.state, dut.sramController.regDone, dut.sramController.io_mem_done,
        $isunknown(sram_dataIn));
  end

  // +stallprobe<n>: every n system clocks while running, print the stall state.
  integer stallprobe = 0, scycles = 0;
  always @(posedge clk_sys) begin
    if (stallprobe != 0 && running) begin
      scycles = scycles + 1;
      if (scycles % stallprobe == 0)
        $display("stallprobe %0d (%0t ps): coreRun %b stallPrg %b stallChr %b ssPending %b chrPendingRead %b chrFull %b | romPending %b respValid %b bufOutEn %b cacheOutEn %b sdramMainReady %b | cpu %b %06x ppu %b %06x | runCycles %0d | sram en %b done %b",
          scycles, $time, dut.coreRun, dut.stallPrg, dut.stallChr, dut._ssSramPort_io_pending, dut._chrPort_io_pendingRead,
          dut._chrPort_io_full, dut.romPending, dut._romBuffer_io_respValid, dut._romBuffer_io_out_enable,
          dut._romCache_io_out_enable, dut._sdramMux_io_main_ready, dut._core_cpumem_read, dut._core_cpumem_addr,
          dut._core_ppumem_read, dut._core_ppumem_addr, dut.statRunCycles, dut._sramArbiter_io_target_enable,
          dut._sramController_io_mem_done);
      if (stallprobe != 0 && running && scycles % stallprobe == 0)
        $display("  core: reset_nes %b cold %b cpu_addr %04x scanline %0d cycle %0d div_cpu %0d div_ppu %0d cpu_tick_count %b corepaused %b flags %016x",
          dut.core.reset_nes, dut.core.cold_reset, dut.core.cpu_addr, dut.core.scanline_ppu, dut.core.ppu_cycle, dut.core.div_cpu,
          dut.core.div_ppu, dut.core.cpu_tick_count, dut.core.corepaused, dut.core.mapper_flags);
    end
  end

  reg [31:0] file_words [0:4194303];
  integer file_size, frontdoor_words, words, i, savestate_frame, load_after, savtest, sav_errors, clearx, longreset;
  reg [31:0] result, status;
  reg error;

  initial begin
    if (!$value$plusargs("file_size%d", file_size)) begin $display("need +file_size"); $finish; end
    words = (file_size + 3) / 4;
    if (!$value$plusargs("frontdoor_words%d", frontdoor_words)) frontdoor_words = words;
    if (!$value$plusargs("frames%d", frames_to_run)) frames_to_run = 10;
    if (!$value$plusargs("press_start%d", press_start)) press_start = 0;
    if (!$value$plusargs("dump_from%d", dump_from)) dump_from = 0;
    if (!$value$plusargs("dump_every%d", dump_every)) dump_every = 0;
    if (!$value$plusargs("savestate%d", savestate_frame)) savestate_frame = 0;
    if (!$value$plusargs("load_after%d", load_after)) load_after = 5;
    if (!$value$plusargs("savtest%d", savtest)) savtest = 0;
    if (!$value$plusargs("xprobe%d", xprobe)) xprobe = 0;
    if (!$value$plusargs("clearx%d", clearx)) clearx = 0;
    if (!$value$plusargs("longreset%d", longreset)) longreset = 20;
    if (!$value$plusargs("sramx%d", sramx)) sramx = 0;
    if (!$value$plusargs("sramtrace%d", sramtrace)) sramtrace = 0;
    if (!$value$plusargs("stallprobe%d", stallprobe)) stallprobe = 0;
    $readmemh("file_words.hex", file_words);
    for (i = 0; i < 262144; i = i + 1) sram[i] = 16'h0000;

    // +longreset<n>: hold the glue in reset for n clocks (default 20). The core
    // clock runs meanwhile (the glue holds the core in reset too), so the X
    // that xsim starts the vendored core with flushes out before any glue
    // register can latch it.
    // +clearx2: while the core is held in reset, its memory strobes read 0 (on the
    // FPGA its registers start at 0 and it issues nothing; in xsim they start at X).
    if (clearx == 2) begin
      force dut._core_ppumem_read = 1'b0; force dut._core_ppumem_write = 1'b0; force dut._core_cpumem_write = 1'b0;
    end
    repeat (longreset) tick();
    locked = 1'b1;
    reset = 1'b0;
    repeat (20) tick();

    // ROM (file id 0) into the SDRAM window
    command(16'h0300, 32'd0, 32'd0, result, error);
    for (i = 0; i < words; i = i + 1) begin
      if (i < frontdoor_words) begin
        wr(32'h30000000 + i * 4, file_words[i]);
      end else if (i * 4 >= dut.romInfo.io_live_chrOffset) begin
        // what the splitter does: CHR byte k at SRAM byte k (16-bit words, little-endian)
        sram[(i * 4 - dut.romInfo.io_live_chrOffset) / 2] = file_words[i][15:0];
        sram[(i * 4 - dut.romInfo.io_live_chrOffset) / 2 + 1] = file_words[i][31:16];
      end else begin
        dut.cdc.mem[i] = file_words[i];
      end
    end
    command(16'h0301, 32'd0, file_size, result, error);
    $display("FILE_WRITE_END 0: error %0d", error);
    rd(32'h0000000C, result); $display("rom info 0x%08x", result);
    rd(32'h00000004, result); $display("flags low 0x%08x", result);
    rd(32'h00000008, result); $display("flags high 0x%08x", result);
    rd(32'h0000001C, result); $display("sizes (KiB) 0x%08x", result);
    // Save file (id 1): no .sav, nothing transferred (or, with +savtest1, 8 KiB of a
    // pattern); states file (id 2): empty
    command(16'h0300, 32'd1, 32'd0, result, error);
    if (savtest != 0) begin
      for (i = 0; i < 2048; i = i + 1) wr(32'h40000000 + i * 4, 32'h5A000000 ^ (i * 32'h01030507));
      command(16'h0301, 32'd1, 32'd8192, result, error);
    end else begin
      command(16'h0301, 32'd1, 32'd0, result, error);
    end
    command(16'h0300, 32'd2, 32'd0, result, error);
    command(16'h0301, 32'd2, 32'd0, result, error);
    command(16'h0102, 32'd0, 32'd0, result, error);
    $display("SETUP_COMPLETE: error %0d", error);
    if (error) $finish;
    for (i = 0; i < 100; i = i + 1) begin
      command(16'h0000, 32'd0, 32'd0, result, error);
      if (result == 3) break;
    end
    $display("GET_STATUS: %0d", result);
    if (clearx == 1) begin
      repeat (20000) tick();
      while (dut.chrPort.busy === 1'b1 || dut.chrPort._queue_io_deq_valid === 1'b1) tick();
      dut.chrPort.demandReads = 3'd0; dut.chrPort.staleReads = 3'd0;
      dut.ssSramPort.demandReads = 3'd0; dut.ssSramPort.staleReads = 3'd0;
      tick();
    end
    if (clearx == 2) begin
      // The core's memory strobes were held at 0 from the start (below); the core
      // stays in reset with the real mapper flags for 20000 more clocks, so that its
      // X flushes, and then they are released.
      repeat (20000) tick();
      release dut._core_ppumem_read; release dut._core_ppumem_write; release dut._core_cpumem_write;
      tick();
    end
    if (clearx != 0) begin
      $display("clearx: X left in coreRun %b chrPending %b ppumem_read %b cpumem_addr %b", $isunknown(dut.coreRun),
        $isunknown(dut._chrPort_io_pendingRead), $isunknown(dut._core_ppumem_read), $isunknown(dut._core_cpumem_addr));
    end
    if (frames_to_run != 0) begin // +frames0: set up only, the core stays halted
      command(16'h0100, 32'd0, 32'd0, result, error);
      command(16'h0200, 32'd1, 32'd0, result, error);
    end
    running = 1'b1;
    $display("running at %0t ps", $time);

    if (savestate_frame != 0) begin
      wait (frame == savestate_frame);
      command(16'h0200, 32'd0, 32'd0, result, error); // focus off (the settings menu)
      rd(32'h00000100, status); $display("status before save 0x%08x", status);
      wr(32'h00000018, 32'd1);
      wr(32'h00000014, 32'h11);
      for (i = 0; i < 400; i = i + 1) begin
        rd(32'h00000100, status);
        if ((status & 32'h680) == 0 && (status & 32'h100) != 0) break;
        repeat (20000) tick();
      end
      $display("status after save 0x%08x (bit 8 save done, bits 14:11 slots) at %0t ps", status, $time);
      command(16'h0200, 32'd1, 32'd0, result, error);
      wait (frame == savestate_frame + load_after);
      command(16'h0200, 32'd0, 32'd0, result, error);
      wr(32'h00000014, 32'h12);
      rd(32'h00000100, status); $display("status right after load request 0x%08x", status);
      for (i = 0; i < 400; i = i + 1) begin
        rd(32'h00000100, status);
        if ((status & 32'h680) == 0) break;
        repeat (20000) tick();
      end
      $display("status after load 0x%08x at %0t ps", status, $time);
      command(16'h0200, 32'd1, 32'd0, result, error);
    end

    wait (frame >= frames_to_run);
    command(16'h0302, 32'd1, 32'd0, result, error); $display("FILE_READ_START 1 -> %0d", result);
    if (savtest != 0) begin
      sav_errors = 0;
      for (i = 0; i < result / 4; i = i + 1) begin
        rd(32'h40000000 + i * 4, status);
        if (status !== (32'h5A000000 ^ (i * 32'h01030507))) begin
          if (sav_errors < 8) $display("SAV MISMATCH word %0d: 0x%08x, expected 0x%08x", i, status, 32'h5A000000 ^ (i * 32'h01030507));
          sav_errors = sav_errors + 1;
        end
      end
      command(16'h0303, 32'd1, 32'd0, result, error);
      $display("SAV TEST: %0d mismatching words", sav_errors);
    end
    command(16'h0302, 32'd2, 32'd0, result, error); $display("FILE_READ_START 2 -> %0d", result);
    $display("done");
    $finish;
  end
endmodule
