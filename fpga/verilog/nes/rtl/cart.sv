// Game Bub: rewritten by fpga/verilog/nes/tools/gamebub_vendor.py (see fpga/verilog/nes/README.md)
import regs_savestates::*;
// Mapper top level selection

// Notes by Kitrinx:
// This module uses bidirectional ports to handle the data out of each mapper.
// Although FPGA's do not use bidirectional wiring internally, the alternative is
// creating a tenticle-monster of wires for each mapper, and muxing them all together.
// As it stands, the compiler will efficiently do it for us with much more readable and
// manageable code if we do it this way, and since not more than one mapper can be active
// at a time, there will be no conflicts.

// SDRAM Locations for various RAM types:
// PRG       = 0....
// CHR       = 10...
// CPU-RAM   = 11100
// CHR-VRAM  = 11101
// CARTRAM   = 1111

module cart_top (
	input             clk,
	input             ce,             // M2
	input             cpu_ce,         // CPU Phi1 clock (several mappers use m2 inverted)
	input             paused,         // This indicates the core is paused so anything using the master clock won't get messed up
	input             reset,
	input             vs_mode,        // Vs. motherboard memory configuration
	input      [63:0] flags,          // Misc flags from ines header {prg_size(3), chr_size(3), mapper(8)}
	input      [15:0] prg_ain,        // Better known as "CPU Address in"
	output reg [24:0] prg_aout,       // PRG Input / Output Address Lines ([25:22] extended Lines [Misc ROM])
	input             prg_read,       // PRG Read / write signals
	input             prg_write,
	input       [7:0] prg_din,        // CPU Data In
	output reg  [7:0] prg_dout,       // CPU Data Out
	input       [7:0] prg_from_ram,   // PRG Data from RAM
	output reg        prg_allow,      // PRG Allow write access
	output reg        prg_bus_write,  // PRG Data Driven
	output reg        prg_conflict,   // PRG Data is ROM & prg_din
	output reg        has_savestate,  // mapper supports savestates
	output reg        prg_conflict_d0,   // PRG Data is ROM & (prg_din | 1)
	output reg        has_flashsaves, // Homebrew mapper that saves to PRG-ROM in flash memory
	input       [9:0] prg_mask,       // PRG Mask for SDRAM translation
	input       [9:0] chr_mask,       // CHR Mask for SDRAM translation
	input             chr_ex,         // chr_addr is from an extra sprite read if high
	input             chr_read,       // Read from CHR
	input             chr_write,      // Write to CHR
	input       [7:0] chr_din,        // PPU Data In
	input      [13:0] chr_ain_orig,   // Better known as "PPU Address in"
	input      [13:0] chr_ain_ex,     // Address for extra sprite fetches
	output reg [21:0] chr_aout,       // CHR Input / Output Address Lines
	output reg  [7:0] chr_dout,       // Value to override CHR data with
	output reg        has_chr_dout,   // True if CHR data should be overridden
	output reg        chr_allow,      // CHR Allow write
	output reg        vram_a10,       // CHR Value for A10 address line
	output reg        vram_ce,        // CHR True if the address should be routed to the internal 2kB VRAM.
	output reg [17:0] mapper_addr,
	input       [7:0] mapper_data_in,
	output reg  [7:0] mapper_data_out,
	output reg        mapper_prg_write,
	output reg        mapper_ovr,
	output reg        irq,
	input      [15:0] audio_in,
	output reg [15:0] audio,          // External Audio
	output reg  [1:0] diskside,
	input             fds_busy,       // FDS Disk Swap Busy
	input             fds_eject,      // FDS Disk Swap Pause
	input             fds_auto_eject, // FDS Auto Swap Enabled
	input       [1:0] max_diskside,   // FDS disk side count
	input             fds_fast,       // FDS disk access speed
	// savestates              
	input       [63:0]  SaveStateBus_Din,
	input       [ 9:0]  SaveStateBus_Adr,
	input               SaveStateBus_wren,
	input               SaveStateBus_rst,
	input               SaveStateBus_load,
	output      [63:0]  SaveStateBus_Dout,
	
	input         Savestate_MAPRAMactive,     
	input  [12:0] Savestate_MAPRAMAddr,     
	input         Savestate_MAPRAMRdEn,    
	input         Savestate_MAPRAMWrEn,    
	input  [7:0]  Savestate_MAPRAMWriteData,
	output [7:0]  Savestate_MAPRAMReadData
);
// Game Bub: declarations used before their original position (tools/hoist_decls.py)
wire prg_allow_b; // Game Bub: hoisted (assigned below)
wire vram_a10_b; // Game Bub: hoisted (assigned below)
wire vram_ce_b; // Game Bub: hoisted (assigned below)
wire chr_allow_b; // Game Bub: hoisted (assigned below)
wire irq_b; // Game Bub: hoisted (assigned below)
wire [15:0] flags_out_b; // Game Bub: hoisted (assigned below)
wire [7:0] prg_dout_b; // Game Bub: hoisted (assigned below)
wire [7:0] chr_dout_b; // Game Bub: hoisted (assigned below)
reg [1023:0] me; // Game Bub: reg (assigned in the always block below; upstream `wire`) // Game Bub: hoisted
localparam SAVESTATE_MODULES    = 14; // Game Bub: hoisted
wire [63:0] SaveStateBus_wired_or[0:SAVESTATE_MODULES-1]; // Game Bub: hoisted

// Game Bub: the upstream tri0 / tri1 buses shared by all mapper instances are
// replaced by one private wire per instance, resolved below (OR for tri0, AND
// for tri1); only the mapper instances Game Bub builds are kept.

wire [13:0] chr_ain = chr_ex ? chr_ain_ex : chr_ain_orig;
wire [2:0] prg_aoute_m413;

// These memories are on the Vs. CPU board rather than any individual ROM
// board. The CPU RAM has only eleven address inputs and therefore repeats four
// times through $6000-$7FFF. All four nametable pages are physically present.
wire vs_prg_ram = vs_mode && (prg_ain[15:13] == 3'b011);
wire vs_nametable_ram = vs_mode && chr_ain[13];
wire [21:0] vs_prg_ram_addr = {9'b11_1100_000, 2'b00, prg_ain[10:0]};
wire [21:0] vs_nametable_ram_addr = {10'b11_1111_1100, chr_ain[11:0]};


// Game Bub: per-instance bus wires (resolved after the instances).
wire prg_allow_b_mmc1;
wire vram_a10_b_mmc1;
wire vram_ce_b_mmc1;
wire chr_allow_b_mmc1;
wire irq_b_mmc1;
wire [21:0] prg_addr_b_mmc1;
wire [21:0] chr_addr_b_mmc1;
wire [15:0] flags_out_b_mmc1;
wire [15:0] audio_out_b_mmc1;
wire [7:0] prg_dout_b_mmc1;
wire prg_allow_b_map28;
wire vram_a10_b_map28;
wire vram_ce_b_map28;
wire chr_allow_b_map28;
wire irq_b_map28;
wire [21:0] prg_addr_b_map28;
wire [21:0] chr_addr_b_map28;
wire [15:0] flags_out_b_map28;
wire [15:0] audio_out_b_map28;
wire [7:0] prg_dout_b_map28;
wire [7:0] chr_dout_b_map28;
wire prg_allow_b_map30;
wire vram_a10_b_map30;
wire vram_ce_b_map30;
wire chr_allow_b_map30;
wire irq_b_map30;
wire [21:0] prg_addr_b_map30;
wire [21:0] chr_addr_b_map30;
wire [15:0] flags_out_b_map30;
wire [15:0] audio_out_b_map30;
wire [7:0] prg_dout_b_map30;
wire prg_allow_b_mmc2;
wire vram_a10_b_mmc2;
wire vram_ce_b_mmc2;
wire chr_allow_b_mmc2;
wire irq_b_mmc2;
wire [21:0] prg_addr_b_mmc2;
wire [21:0] chr_addr_b_mmc2;
wire [15:0] flags_out_b_mmc2;
wire [15:0] audio_out_b_mmc2;
wire [7:0] prg_dout_b_mmc2;
wire prg_allow_b_mmc3;
wire vram_a10_b_mmc3;
wire vram_ce_b_mmc3;
wire chr_allow_b_mmc3;
wire irq_b_mmc3;
wire [21:0] prg_addr_b_mmc3;
wire [21:0] chr_addr_b_mmc3;
wire [15:0] flags_out_b_mmc3;
wire [15:0] audio_out_b_mmc3;
wire [7:0] prg_dout_b_mmc3;
wire prg_allow_b_mmc4;
wire vram_a10_b_mmc4;
wire vram_ce_b_mmc4;
wire chr_allow_b_mmc4;
wire irq_b_mmc4;
wire [21:0] prg_addr_b_mmc4;
wire [21:0] chr_addr_b_mmc4;
wire [15:0] flags_out_b_mmc4;
wire [15:0] audio_out_b_mmc4;
wire [7:0] prg_dout_b_mmc4;
wire prg_allow_b_map13;
wire vram_a10_b_map13;
wire vram_ce_b_map13;
wire chr_allow_b_map13;
wire irq_b_map13;
wire [21:0] prg_addr_b_map13;
wire [21:0] chr_addr_b_map13;
wire [15:0] flags_out_b_map13;
wire [15:0] audio_out_b_map13;
wire [7:0] prg_dout_b_map13;
wire prg_allow_b_map34;
wire vram_a10_b_map34;
wire vram_ce_b_map34;
wire chr_allow_b_map34;
wire irq_b_map34;
wire [21:0] prg_addr_b_map34;
wire [21:0] chr_addr_b_map34;
wire [15:0] flags_out_b_map34;
wire [15:0] audio_out_b_map34;
wire [7:0] prg_dout_b_map34;
wire prg_allow_b_map66;
wire vram_a10_b_map66;
wire vram_ce_b_map66;
wire chr_allow_b_map66;
wire irq_b_map66;
wire [21:0] prg_addr_b_map66;
wire [21:0] chr_addr_b_map66;
wire [15:0] flags_out_b_map66;
wire [15:0] audio_out_b_map66;
wire [7:0] prg_dout_b_map66;
wire prg_allow_b_map71;
wire vram_a10_b_map71;
wire vram_ce_b_map71;
wire chr_allow_b_map71;
wire irq_b_map71;
wire [21:0] prg_addr_b_map71;
wire [21:0] chr_addr_b_map71;
wire [15:0] flags_out_b_map71;
wire [15:0] audio_out_b_map71;
wire [7:0] prg_dout_b_map71;
wire prg_allow_b_map77;
wire vram_a10_b_map77;
wire vram_ce_b_map77;
wire chr_allow_b_map77;
wire irq_b_map77;
wire [21:0] prg_addr_b_map77;
wire [21:0] chr_addr_b_map77;
wire [15:0] flags_out_b_map77;
wire [15:0] audio_out_b_map77;
wire [7:0] prg_dout_b_map77;
wire prg_allow_b_map78;
wire vram_a10_b_map78;
wire vram_ce_b_map78;
wire chr_allow_b_map78;
wire irq_b_map78;
wire [21:0] prg_addr_b_map78;
wire [21:0] chr_addr_b_map78;
wire [15:0] flags_out_b_map78;
wire [15:0] audio_out_b_map78;
wire [7:0] prg_dout_b_map78;
wire prg_allow_b_map79;
wire vram_a10_b_map79;
wire vram_ce_b_map79;
wire chr_allow_b_map79;
wire irq_b_map79;
wire [21:0] prg_addr_b_map79;
wire [21:0] chr_addr_b_map79;
wire [15:0] flags_out_b_map79;
wire [15:0] audio_out_b_map79;
wire [7:0] prg_dout_b_map79;
wire prg_allow_b_map89;
wire vram_a10_b_map89;
wire vram_ce_b_map89;
wire chr_allow_b_map89;
wire irq_b_map89;
wire [21:0] prg_addr_b_map89;
wire [21:0] chr_addr_b_map89;
wire [15:0] flags_out_b_map89;
wire [15:0] audio_out_b_map89;
wire [7:0] prg_dout_b_map89;
wire prg_allow_b_map107;
wire vram_a10_b_map107;
wire vram_ce_b_map107;
wire chr_allow_b_map107;
wire irq_b_map107;
wire [21:0] prg_addr_b_map107;
wire [21:0] chr_addr_b_map107;
wire [15:0] flags_out_b_map107;
wire [15:0] audio_out_b_map107;
wire [7:0] prg_dout_b_map107;
wire prg_allow_b_map165;
wire vram_a10_b_map165;
wire vram_ce_b_map165;
wire chr_allow_b_map165;
wire irq_b_map165;
wire [21:0] prg_addr_b_map165;
wire [21:0] chr_addr_b_map165;
wire [15:0] flags_out_b_map165;
wire [15:0] audio_out_b_map165;
wire [7:0] prg_dout_b_map165;
wire prg_allow_b_rambo1;
wire vram_a10_b_rambo1;
wire vram_ce_b_rambo1;
wire chr_allow_b_rambo1;
wire irq_b_rambo1;
wire [21:0] prg_addr_b_rambo1;
wire [21:0] chr_addr_b_rambo1;
wire [15:0] flags_out_b_rambo1;
wire [15:0] audio_out_b_rambo1;
wire [7:0] prg_dout_b_rambo1;
wire prg_allow_b_nesev;
wire vram_a10_b_nesev;
wire vram_ce_b_nesev;
wire chr_allow_b_nesev;
wire irq_b_nesev;
wire [21:0] prg_addr_b_nesev;
wire [21:0] chr_addr_b_nesev;
wire [15:0] flags_out_b_nesev;
wire [15:0] audio_out_b_nesev;
wire [7:0] prg_dout_b_nesev;


//*****************************************************************************//
// Name   : MMC1                                                               //
// Mappers: 1, 155, 171 (hard wired vertical mirroring)                        //
// Status : Working                                                            //
// Notes  :                                                                    //
// Games  : Simon's Quest                                                      //
//*****************************************************************************//
MMC1 mmc1(
	.clk        (clk),
	.ce         (ce),
	.enable     (me[171] | me[155] | me[1]),
	.flags      (flags),
	.prg_ain    (prg_ain),
	.prg_aout_b (prg_addr_b_mmc1),
	.prg_read   (prg_read),
	.prg_write  (prg_write),
	.prg_din    (prg_din),
	.prg_dout_b (prg_dout_b_mmc1),
	.prg_allow_b(prg_allow_b_mmc1),
	.chr_ain    (chr_ain),
	.chr_aout_b (chr_addr_b_mmc1),
	.chr_read   (chr_read),
	.chr_allow_b(chr_allow_b_mmc1),
	.vram_a10_b (vram_a10_b_mmc1),
	.vram_ce_b  (vram_ce_b_mmc1),
	.irq_b      (irq_b_mmc1),
	.flags_out_b(flags_out_b_mmc1),
	.audio_in   (audio_in),
	.audio_b    (audio_out_b_mmc1),
	// savestates
	.SaveStateBus_Din  (SaveStateBus_Din ), 
	.SaveStateBus_Adr  (SaveStateBus_Adr ),
	.SaveStateBus_wren (SaveStateBus_wren),
	.SaveStateBus_rst  (SaveStateBus_rst ),
	.SaveStateBus_load (SaveStateBus_load ),
	.SaveStateBus_Dout (SaveStateBus_wired_or[0])
);


//*****************************************************************************//
// Name   : Tepples                                                            //
// Mappers: 0, 2, 3, 7, 28, 94, 97, 180, 185                                   //
// Status : Working                                                            //
// Notes  : This mapper relies on open bus and bus conflict behavior.          //
// Games  : Donkey Kong                                                        //
//*****************************************************************************//
wire mapper28_en = me[0] | me[2] | me[3] | me[7] | me[94] | me[97] | me[180] | me[185] | me[28];
Mapper28 map28(
	.clk        (clk),
	.ce         (ce),
	.enable     (mapper28_en & ~reset),
	.flags      (flags),
	.prg_ain    (prg_ain),
	.prg_aout_b (prg_addr_b_map28),
	.prg_read   (prg_read),
	.prg_write  (prg_write),
	.prg_din    (prg_din),
	.prg_dout_b (prg_dout_b_map28),
	.prg_allow_b(prg_allow_b_map28),
	.chr_ain    (chr_ain),
	.chr_aout_b (chr_addr_b_map28),
	.chr_read   (chr_read),
	.chr_dout_b (chr_dout_b_map28), // Special port
	.chr_allow_b(chr_allow_b_map28),
	.vram_a10_b (vram_a10_b_map28),
	.vram_ce_b  (vram_ce_b_map28),
	.irq_b      (irq_b_map28),
	.flags_out_b(flags_out_b_map28),
	.audio_in   (audio_in),
	.audio_b    (audio_out_b_map28),
	// savestates
	.SaveStateBus_Din  (SaveStateBus_Din ), 
	.SaveStateBus_Adr  (SaveStateBus_Adr ),
	.SaveStateBus_wren (SaveStateBus_wren),
	.SaveStateBus_rst  (SaveStateBus_rst ),
	.SaveStateBus_load (SaveStateBus_load ),
	.SaveStateBus_Dout (SaveStateBus_wired_or[1])
);


//*****************************************************************************//
// Name   : UNROM 512                                                          //
// Mappers: 30                                                                 //
// Status : Self flashing except sector erase                                  //
// Notes  : Homebrew mapper                                                    //
// Games  : More Glider                                                        //
//*****************************************************************************//
Mapper30 map30(
	.clk        (clk),
	.ce         (ce),
	.enable     (me[30]),
	.flags      (flags),
	.prg_ain    (prg_ain),
	.prg_aout_b (prg_addr_b_map30),
	.prg_read   (prg_read),
	.prg_write  (prg_write),
	.prg_din    (prg_din),
	.prg_dout_b (prg_dout_b_map30),
	.prg_allow_b(prg_allow_b_map30),
	.chr_ain    (chr_ain),
	.chr_aout_b (chr_addr_b_map30),
	.chr_read   (chr_read),
	.chr_allow_b(chr_allow_b_map30),
	.vram_a10_b (vram_a10_b_map30),
	.vram_ce_b  (vram_ce_b_map30),
	.irq_b      (irq_b_map30),
	.flags_out_b(flags_out_b_map30),
	.audio_in   (audio_in),
	.audio_b    (audio_out_b_map30),
	// savestates
	.SaveStateBus_Din  (SaveStateBus_Din ), 
	.SaveStateBus_Adr  (SaveStateBus_Adr ),
	.SaveStateBus_wren (SaveStateBus_wren),
	.SaveStateBus_rst  (SaveStateBus_rst ),
	.SaveStateBus_load (SaveStateBus_load ),
	.SaveStateBus_Dout (SaveStateBus_wired_or[2])
);


//*****************************************************************************//
// Name   : MMC2                                                               //
// Mappers: 9                                                                  //
// Status :                                                                    //
// Notes  : Working                                                            //
// Games  : Mike Tyson's Punch-Out                                             //
//*****************************************************************************//
MMC2 mmc2(
	.clk        (clk),
	.ce         (ce),
	.enable     (me[9]),
	.flags      (flags),
	.prg_ain    (prg_ain),
	.prg_aout_b (prg_addr_b_mmc2),
	.prg_read   (prg_read),
	.prg_write  (prg_write),
	.prg_din    (prg_din),
	.prg_dout_b (prg_dout_b_mmc2),
	.prg_allow_b(prg_allow_b_mmc2),
	.chr_ain    (chr_ain),
	.chr_aout_b (chr_addr_b_mmc2),
	.chr_read   (chr_read),
	.chr_allow_b(chr_allow_b_mmc2),
	.vram_a10_b (vram_a10_b_mmc2),
	.vram_ce_b  (vram_ce_b_mmc2),
	.irq_b      (irq_b_mmc2),
	.flags_out_b(flags_out_b_mmc2),
	.audio_in   (audio_in),
	.audio_b    (audio_out_b_mmc2),
	// Special ports
	.chr_ain_o  (chr_ain_orig),
	.paused     (paused),
	// savestates
	.SaveStateBus_Din  (SaveStateBus_Din ), 
	.SaveStateBus_Adr  (SaveStateBus_Adr ),
	.SaveStateBus_wren (SaveStateBus_wren),
	.SaveStateBus_rst  (SaveStateBus_rst ),
	.SaveStateBus_load (SaveStateBus_load ),
	.SaveStateBus_Dout (SaveStateBus_wired_or[3])
);


//*****************************************************************************//
// Name   : MMC3                                                               //
// Mappers: 4, 33, 37, 45, 47, 48, 52, 74, 76, 80, 82, 88, 95, 112, 118, 119,  //
//          154, 189, 191, 192, 194, 195, 196, 205, 206, 207, 208, 268         //
// Status : Working -- Blaarg IRQ timing test fails, but may be submapper      //
// Notes  : While currently working well, this mapper could use a full review. //
// Games  : Crystalis, Battletoads                                             //
//*****************************************************************************//
wire mmc3_en = me[118] | me[119] | me[47] | me[206] | me[112] | me[88] | me[154] | me[95]
	| me[76] | me[80] | me[82] | me[207] | me[48] | me[33] | me[37] | me[74] | me[191]
	| me[192] | me[194] | me[195] | me[196] | me[4] | me[189] | me[268] | me[205] | me[208]
	| me[45] | me[52];

MMC3 mmc3 (
	.clk        (clk),
	.ce         (ce),
	.enable     (mmc3_en),
	.flags      (flags),
	.prg_ain    (prg_ain),
	.prg_aout_b (prg_addr_b_mmc3),
	.prg_read   (prg_read),
	.prg_write  (prg_write),
	.prg_din    (prg_din),
	.prg_dout_b (prg_dout_b_mmc3),
	.prg_allow_b(prg_allow_b_mmc3),
	.chr_ain    (chr_ain),
	.chr_aout_b (chr_addr_b_mmc3),
	.chr_read   (chr_read),
	.chr_allow_b(chr_allow_b_mmc3),
	.vram_a10_b (vram_a10_b_mmc3),
	.vram_ce_b  (vram_ce_b_mmc3),
	.irq_b      (irq_b_mmc3),
	.flags_out_b(flags_out_b_mmc3),
	.audio_in   (audio_in),
	.audio_b    (audio_out_b_mmc3),
	// Special ports
	.chr_ain_o  (chr_ain_orig),
	.m2_inv     (cpu_ce),
	.paused     (paused),
	// savestates
	.SaveStateBus_Din  (SaveStateBus_Din ), 
	.SaveStateBus_Adr  (SaveStateBus_Adr ),
	.SaveStateBus_wren (SaveStateBus_wren),
	.SaveStateBus_rst  (SaveStateBus_rst ),
	.SaveStateBus_load (SaveStateBus_load ),
	.SaveStateBus_Dout (SaveStateBus_wired_or[4])
);


//*****************************************************************************//
// Name   : MMC4                                                               //
// Mappers: 10                                                                 //
// Status : Working                                                            //
// Notes  :                                                                    //
// Games  : Fire Emblem                                                        //
//*****************************************************************************//
MMC4 mmc4(
	.clk        (clk),
	.ce         (ce),
	.enable     (me[10]),
	.flags      (flags),
	.prg_ain    (prg_ain),
	.prg_aout_b (prg_addr_b_mmc4),
	.prg_read   (prg_read),
	.prg_write  (prg_write),
	.prg_din    (prg_din),
	.prg_dout_b (prg_dout_b_mmc4),
	.prg_allow_b(prg_allow_b_mmc4),
	.chr_ain    (chr_ain),
	.chr_aout_b (chr_addr_b_mmc4),
	.chr_read   (chr_read),
	.chr_allow_b(chr_allow_b_mmc4),
	.vram_a10_b (vram_a10_b_mmc4),
	.vram_ce_b  (vram_ce_b_mmc4),
	.irq_b      (irq_b_mmc4),
	.flags_out_b(flags_out_b_mmc4),
	.audio_in   (audio_in),
	.audio_b    (audio_out_b_mmc4),
	// Special ports
	.chr_ain_o  (chr_ain_orig),
	.paused     (paused),
	// savestates
	.SaveStateBus_Din  (SaveStateBus_Din ), 
	.SaveStateBus_Adr  (SaveStateBus_Adr ),
	.SaveStateBus_wren (SaveStateBus_wren),
	.SaveStateBus_rst  (SaveStateBus_rst ),
	.SaveStateBus_load (SaveStateBus_load ),
	.SaveStateBus_Dout (SaveStateBus_wired_or[5])
);


//*****************************************************************************//
// Name   : CPROM                                                              //
// Mappers: 13                                                                 //
// Status : Working                                                            //
// Notes  :                                                                    //
// Games  : Videomation                                                        //
//*****************************************************************************//
Mapper13 map13(
	.clk        (clk),
	.ce         (ce),
	.enable     (me[13]),
	.flags      (flags),
	.prg_ain    (prg_ain),
	.prg_aout_b (prg_addr_b_map13),
	.prg_read   (prg_read),
	.prg_write  (prg_write),
	.prg_din    (prg_din),
	.prg_dout_b (prg_dout_b_map13),
	.prg_allow_b(prg_allow_b_map13),
	.chr_ain    (chr_ain),
	.chr_aout_b (chr_addr_b_map13),
	.chr_read   (chr_read),
	.chr_allow_b(chr_allow_b_map13),
	.vram_a10_b (vram_a10_b_map13),
	.vram_ce_b  (vram_ce_b_map13),
	.irq_b      (irq_b_map13),
	.flags_out_b(flags_out_b_map13),
	.audio_in   (audio_in),
	.audio_b    (audio_out_b_map13)
);


//*****************************************************************************//
// Name   : BNROM                                                              //
// Mappers: 34                                                                 //
// Status : Working                                                            //
// Notes  :                                                                    //
// Games  : Mashou, Deadly Towers                                              //
//*****************************************************************************//
Mapper34 map34(
	.clk        (clk),
	.ce         (ce),
	.enable     (me[34]),
	.flags      (flags),
	.prg_ain    (prg_ain),
	.prg_aout_b (prg_addr_b_map34),
	.prg_read   (prg_read),
	.prg_write  (prg_write),
	.prg_din    (prg_din),
	.prg_dout_b (prg_dout_b_map34),
	.prg_allow_b(prg_allow_b_map34),
	.chr_ain    (chr_ain),
	.chr_aout_b (chr_addr_b_map34),
	.chr_read   (chr_read),
	.chr_allow_b(chr_allow_b_map34),
	.vram_a10_b (vram_a10_b_map34),
	.vram_ce_b  (vram_ce_b_map34),
	.irq_b      (irq_b_map34),
	.flags_out_b(flags_out_b_map34),
	.audio_in   (audio_in),
	.audio_b    (audio_out_b_map34),
	// savestates
	.SaveStateBus_Din  (SaveStateBus_Din ), 
	.SaveStateBus_Adr  (SaveStateBus_Adr ),
	.SaveStateBus_wren (SaveStateBus_wren),
	.SaveStateBus_rst  (SaveStateBus_rst ),
	.SaveStateBus_load (SaveStateBus_load ),
	.SaveStateBus_Dout (SaveStateBus_wired_or[6])
);


//*********************************************************************************//
// Name   : GxROM                                                                  //
// Mappers: 11, 38, 46, 66, 86, 87, 101, 140, 144                                  //
// Status : 38/66 - Working, 38/87/101/140/144 - Needs eval, 86 - No Audio Samples //
// Notes  :                                                                        //
// Games  : Doraemon, Dragon Power, Sidewinder (145), Taiwan Mahjong 16 (149)      //
//*********************************************************************************//
wire mapper66_en = me[11] | me[38] | me[46] | me[86] | me[87] | me[101] | me[140] | me[66] | me[144] | me[145] | me[149];
Mapper66 map66(
	.clk        (clk),
	.ce         (ce),
	.enable     (mapper66_en),
	.flags      (flags),
	.prg_ain    (prg_ain),
	.prg_aout_b (prg_addr_b_map66),
	.prg_read   (prg_read),
	.prg_write  (prg_write),
	.prg_din    (prg_din),
	.prg_dout_b (prg_dout_b_map66),
	.prg_allow_b(prg_allow_b_map66),
	.chr_ain    (chr_ain),
	.chr_aout_b (chr_addr_b_map66),
	.chr_read   (chr_read),
	.chr_allow_b(chr_allow_b_map66),
	.vram_a10_b (vram_a10_b_map66),
	.vram_ce_b  (vram_ce_b_map66),
	.irq_b      (irq_b_map66),
	.flags_out_b(flags_out_b_map66),
	.audio_in   (audio_in),
	.audio_b    (audio_out_b_map66),
	// savestates
	.SaveStateBus_Din  (SaveStateBus_Din ), 
	.SaveStateBus_Adr  (SaveStateBus_Adr ),
	.SaveStateBus_wren (SaveStateBus_wren),
	.SaveStateBus_rst  (SaveStateBus_rst ),
	.SaveStateBus_load (SaveStateBus_load ),
	.SaveStateBus_Dout (SaveStateBus_wired_or[7])
);


//*****************************************************************************//
// Name   : Codemasters/Camerica                                               //
// Mappers: 71, 232                                                            //
// Status : Working                                                            //
// Notes  :                                                                    //
// Games  : Micro Machines, Big Nose the Caveman                               //
//*****************************************************************************//
Mapper71 map71(
	.clk        (clk),
	.ce         (ce),
	.enable     (me[71] | me[232]),
	.flags      (flags),
	.prg_ain    (prg_ain),
	.prg_aout_b (prg_addr_b_map71),
	.prg_read   (prg_read),
	.prg_write  (prg_write),
	.prg_din    (prg_din),
	.prg_dout_b (prg_dout_b_map71),
	.prg_allow_b(prg_allow_b_map71),
	.chr_ain    (chr_ain),
	.chr_aout_b (chr_addr_b_map71),
	.chr_read   (chr_read),
	.chr_allow_b(chr_allow_b_map71),
	.vram_a10_b (vram_a10_b_map71),
	.vram_ce_b  (vram_ce_b_map71),
	.irq_b      (irq_b_map71),
	.flags_out_b(flags_out_b_map71),
	.audio_in   (audio_in),
	.audio_b    (audio_out_b_map71),
	// savestates
	.SaveStateBus_Din  (SaveStateBus_Din ), 
	.SaveStateBus_Adr  (SaveStateBus_Adr ),
	.SaveStateBus_wren (SaveStateBus_wren),
	.SaveStateBus_rst  (SaveStateBus_rst ),
	.SaveStateBus_load (SaveStateBus_load ),
	.SaveStateBus_Dout (SaveStateBus_wired_or[8])
);


//*****************************************************************************//
// Name   : Mapper 77                                                          //
// Mappers: 77                                                                 //
// Status : Needs Evaluation                                                   //
// Notes  :                                                                    //
// Games  : Napoleon Senki                                                     //
//*****************************************************************************//
Mapper77 map77(
	.clk        (clk),
	.ce         (ce),
	.enable     (me[77]),
	.flags      (flags),
	.prg_ain    (prg_ain),
	.prg_aout_b (prg_addr_b_map77),
	.prg_read   (prg_read),
	.prg_write  (prg_write),
	.prg_din    (prg_din),
	.prg_dout_b (prg_dout_b_map77),
	.prg_allow_b(prg_allow_b_map77),
	.chr_ain    (chr_ain),
	.chr_aout_b (chr_addr_b_map77),
	.chr_read   (chr_read),
	.chr_allow_b(chr_allow_b_map77),
	.vram_a10_b (vram_a10_b_map77),
	.vram_ce_b  (vram_ce_b_map77),
	.irq_b      (irq_b_map77),
	.flags_out_b(flags_out_b_map77),
	.audio_in   (audio_in),
	.audio_b    (audio_out_b_map77),
	// savestates
	.SaveStateBus_Din  (SaveStateBus_Din ), 
	.SaveStateBus_Adr  (SaveStateBus_Adr ),
	.SaveStateBus_wren (SaveStateBus_wren),
	.SaveStateBus_rst  (SaveStateBus_rst ),
	.SaveStateBus_load (SaveStateBus_load ),
	.SaveStateBus_Dout (SaveStateBus_wired_or[9])
);


//*****************************************************************************//
// Name   : Holy Diver, NTDEC N715021                                          //
// Mappers: 78, 70, 152, 81                                                    //
// Status : Needs testing overall                                              //
// Notes  : Submapper 1 Requires NES 2.0                                       //
// Games  : Holy Diver, Uchuusent, Super Gun                                   //
//*****************************************************************************//
Mapper78 map78(
	.clk        (clk),
	.ce         (ce),
	.enable     (me[152] | me[70] | me[78] | me[81]),
	.flags      (flags),
	.prg_ain    (prg_ain),
	.prg_aout_b (prg_addr_b_map78),
	.prg_read   (prg_read),
	.prg_write  (prg_write),
	.prg_din    (prg_din),
	.prg_dout_b (prg_dout_b_map78),
	.prg_allow_b(prg_allow_b_map78),
	.chr_ain    (chr_ain),
	.chr_aout_b (chr_addr_b_map78),
	.chr_read   (chr_read),
	.chr_allow_b(chr_allow_b_map78),
	.vram_a10_b (vram_a10_b_map78),
	.vram_ce_b  (vram_ce_b_map78),
	.irq_b      (irq_b_map78),
	.flags_out_b(flags_out_b_map78),
	.audio_in   (audio_in),
	.audio_b    (audio_out_b_map78),
	// savestates
	.SaveStateBus_Din  (SaveStateBus_Din ), 
	.SaveStateBus_Adr  (SaveStateBus_Adr ),
	.SaveStateBus_wren (SaveStateBus_wren),
	.SaveStateBus_rst  (SaveStateBus_rst ),
	.SaveStateBus_load (SaveStateBus_load ),
	.SaveStateBus_Dout (SaveStateBus_wired_or[10])
);


//*****************************************************************************//
// Name   : NINA                                                               //
// Mappers: 79, 113, 133, 146, 148                                             //
// Status : Working                                                            //
// Notes  : 133 uses simplified (72 pin) version, 146 Duplicate of 79?         //
// Games  : Tiles of Fate, Dudes with Attitude, Krazy Kreatures,               //
//          Twin Eagle (146), Mahjong World (148), Jovial Race (133)           //
//*****************************************************************************//
Mapper79 map79(
	.clk        (clk),
	.ce         (ce),
	.enable     (me[79] | me[113] | me[133] | me[146] | me[148]),
	.flags      (flags),
	.prg_ain    (prg_ain),
	.prg_aout_b (prg_addr_b_map79),
	.prg_read   (prg_read),
	.prg_write  (prg_write),
	.prg_din    (prg_din),
	.prg_dout_b (prg_dout_b_map79),
	.prg_allow_b(prg_allow_b_map79),
	.chr_ain    (chr_ain),
	.chr_aout_b (chr_addr_b_map79),
	.chr_read   (chr_read),
	.chr_allow_b(chr_allow_b_map79),
	.vram_a10_b (vram_a10_b_map79),
	.vram_ce_b  (vram_ce_b_map79),
	.irq_b      (irq_b_map79),
	.flags_out_b(flags_out_b_map79),
	.audio_in   (audio_in),
	.audio_b    (audio_out_b_map79),
	// savestates
	.SaveStateBus_Din  (SaveStateBus_Din ), 
	.SaveStateBus_Adr  (SaveStateBus_Adr ),
	.SaveStateBus_wren (SaveStateBus_wren),
	.SaveStateBus_rst  (SaveStateBus_rst ),
	.SaveStateBus_load (SaveStateBus_load ),
	.SaveStateBus_Dout (SaveStateBus_wired_or[11])
);


//*****************************************************************************//
// Name   : Sunsoft                                                            //
// Mappers: 89, 93, 184                                                        //
// Status : Needs Evaluation                                                   //
// Notes  :                                                                    //
// Games  : Tenka no Goikenban                                                 //
//*****************************************************************************//
Mapper89 map89(
	.clk        (clk),
	.ce         (ce),
	.enable     (me[89] | me[93] | me[184]),
	.flags      (flags),
	.prg_ain    (prg_ain),
	.prg_aout_b (prg_addr_b_map89),
	.prg_read   (prg_read),
	.prg_write  (prg_write),
	.prg_din    (prg_din),
	.prg_dout_b (prg_dout_b_map89),
	.prg_allow_b(prg_allow_b_map89),
	.chr_ain    (chr_ain),
	.chr_aout_b (chr_addr_b_map89),
	.chr_read   (chr_read),
	.chr_allow_b(chr_allow_b_map89),
	.vram_a10_b (vram_a10_b_map89),
	.vram_ce_b  (vram_ce_b_map89),
	.irq_b      (irq_b_map89),
	.flags_out_b(flags_out_b_map89),
	.audio_in   (audio_in),
	.audio_b    (audio_out_b_map89),
	// savestates
	.SaveStateBus_Din  (SaveStateBus_Din ), 
	.SaveStateBus_Adr  (SaveStateBus_Adr ),
	.SaveStateBus_wren (SaveStateBus_wren),
	.SaveStateBus_rst  (SaveStateBus_rst ),
	.SaveStateBus_load (SaveStateBus_load ),
	.SaveStateBus_Dout (SaveStateBus_wired_or[12])
);


//*****************************************************************************//
// Name   : Magic Dragon                                                       //
// Mappers: 107                                                                //
// Status : Needs Evaluation                                                   //
// Notes  :                                                                    //
// Games  : Magic Dragon                                                       //
//*****************************************************************************//
Mapper107 map107(
	.clk        (clk),
	.ce         (ce),
	.enable     (me[107]),
	.flags      (flags),
	.prg_ain    (prg_ain),
	.prg_aout_b (prg_addr_b_map107),
	.prg_read   (prg_read),
	.prg_write  (prg_write),
	.prg_din    (prg_din),
	.prg_dout_b (prg_dout_b_map107),
	.prg_allow_b(prg_allow_b_map107),
	.chr_ain    (chr_ain),
	.chr_aout_b (chr_addr_b_map107),
	.chr_read   (chr_read),
	.chr_allow_b(chr_allow_b_map107),
	.vram_a10_b (vram_a10_b_map107),
	.vram_ce_b  (vram_ce_b_map107),
	.irq_b      (irq_b_map107),
	.flags_out_b(flags_out_b_map107),
	.audio_in   (audio_in),
	.audio_b    (audio_out_b_map107)
);


//*****************************************************************************//
// Name   : Mapper 165                                                         //
// Mappers: 165                                                                //
// Status : Working                                                            //
// Notes  : Possibly merge-able with MMC3, only used for one bootleg game      //
// Games  : Fire Emblem (unl)                                                  //
//*****************************************************************************//
Mapper165 map165(
	.clk        (clk),
	.ce         (ce),
	.enable     (me[165]),
	.flags      (flags),
	.prg_ain    (prg_ain),
	.prg_aout_b (prg_addr_b_map165),
	.prg_read   (prg_read),
	.prg_write  (prg_write),
	.prg_din    (prg_din),
	.prg_dout_b (prg_dout_b_map165),
	.prg_allow_b(prg_allow_b_map165),
	.chr_ain    (chr_ain),
	.chr_aout_b (chr_addr_b_map165),
	.chr_read   (chr_read),
	.chr_allow_b(chr_allow_b_map165),
	.vram_a10_b (vram_a10_b_map165),
	.vram_ce_b  (vram_ce_b_map165),
	.irq_b      (irq_b_map165),
	.flags_out_b(flags_out_b_map165),
	.audio_in   (audio_in),
	.audio_b    (audio_out_b_map165),
	// Special ports
	.chr_ain_o  (chr_ain_orig),
	.m2_inv     (cpu_ce),
	.paused     (paused)
);


//*****************************************************************************//
// Name   : RAMBO1 (Tengen MMC3)                                               //
// Mappers: 64, 158                                                            //
// Status : Needs testing.  Irq might be slightly off.                         //
// Notes  : Consider merging with MMC3                                         //
// Games  : Rolling Thunder, Klax, Skull and Crossbones, Alien Syndrome (158)  //
//*****************************************************************************//
Rambo1 rambo1(
	.clk        (clk),
	.ce         (ce),
	.enable     (me[64] | me[158]),
	.flags      (flags),
	.prg_ain    (prg_ain),
	.prg_aout_b (prg_addr_b_rambo1),
	.prg_read   (prg_read),
	.prg_write  (prg_write),
	.prg_din    (prg_din),
	.prg_dout_b (prg_dout_b_rambo1),
	.prg_allow_b(prg_allow_b_rambo1),
	.chr_ain    (chr_ain),
	.chr_aout_b (chr_addr_b_rambo1),
	.chr_read   (chr_read),
	.chr_allow_b(chr_allow_b_rambo1),
	.vram_a10_b (vram_a10_b_rambo1),
	.vram_ce_b  (vram_ce_b_rambo1),
	.irq_b      (irq_b_rambo1),
	.flags_out_b(flags_out_b_rambo1),
	.audio_in   (audio_in),
	.audio_b    (audio_out_b_rambo1),
	// Special ports
	.chr_ain_o  (chr_ain_orig),
	// savestates
	.SaveStateBus_Din  (SaveStateBus_Din ), 
	.SaveStateBus_Adr  (SaveStateBus_Adr ),
	.SaveStateBus_wren (SaveStateBus_wren),
	.SaveStateBus_rst  (SaveStateBus_rst ),
	.SaveStateBus_load (SaveStateBus_load ),
	.SaveStateBus_Dout (SaveStateBus_wired_or[13])
);


//*****************************************************************************//
// Name   : NesEvent                                                           //
// Mappers: 105                                                                //
// Status : Working                                                            //
// Notes  : This wraps the MMC1 mapper, consider merging more elegantly        //
// Games  : Nintendo World Championships 1990 (start hack)                     //
//*****************************************************************************//
NesEvent nesev(
	.clk        (clk),
	.ce         (ce),
	.enable     (me[105]),
	.flags      (flags),
	.prg_ain    (prg_ain),
	.prg_aout_b (prg_addr_b_nesev),
	.prg_read   (prg_read),
	.prg_write  (prg_write),
	.prg_din    (prg_din),
	.prg_dout_b (prg_dout_b_nesev),
	.prg_allow_b(prg_allow_b_nesev),
	.chr_ain    (chr_ain),
	.chr_aout_b (chr_addr_b_nesev),
	.chr_read   (chr_read),
	.chr_allow_b(chr_allow_b_nesev),
	.vram_a10_b (vram_a10_b_nesev),
	.vram_ce_b  (vram_ce_b_nesev),
	.irq_b      (irq_b_nesev),
	.flags_out_b(flags_out_b_nesev),
	.audio_in   (audio_in),
	.audio_b    (audio_out_b_nesev)
);

// Game Bub: bus resolution (tri0 -> OR, tri1 -> AND).
assign prg_allow_b = prg_allow_b_mmc1 | prg_allow_b_map28 | prg_allow_b_map30 | prg_allow_b_mmc2 | prg_allow_b_mmc3 | prg_allow_b_mmc4 | prg_allow_b_map13 | prg_allow_b_map34 | prg_allow_b_map66 | prg_allow_b_map71 | prg_allow_b_map77 | prg_allow_b_map78 | prg_allow_b_map79 | prg_allow_b_map89 | prg_allow_b_map107 | prg_allow_b_map165 | prg_allow_b_rambo1 | prg_allow_b_nesev;
assign vram_a10_b = vram_a10_b_mmc1 | vram_a10_b_map28 | vram_a10_b_map30 | vram_a10_b_mmc2 | vram_a10_b_mmc3 | vram_a10_b_mmc4 | vram_a10_b_map13 | vram_a10_b_map34 | vram_a10_b_map66 | vram_a10_b_map71 | vram_a10_b_map77 | vram_a10_b_map78 | vram_a10_b_map79 | vram_a10_b_map89 | vram_a10_b_map107 | vram_a10_b_map165 | vram_a10_b_rambo1 | vram_a10_b_nesev;
assign vram_ce_b = vram_ce_b_mmc1 | vram_ce_b_map28 | vram_ce_b_map30 | vram_ce_b_mmc2 | vram_ce_b_mmc3 | vram_ce_b_mmc4 | vram_ce_b_map13 | vram_ce_b_map34 | vram_ce_b_map66 | vram_ce_b_map71 | vram_ce_b_map77 | vram_ce_b_map78 | vram_ce_b_map79 | vram_ce_b_map89 | vram_ce_b_map107 | vram_ce_b_map165 | vram_ce_b_rambo1 | vram_ce_b_nesev;
assign chr_allow_b = chr_allow_b_mmc1 | chr_allow_b_map28 | chr_allow_b_map30 | chr_allow_b_mmc2 | chr_allow_b_mmc3 | chr_allow_b_mmc4 | chr_allow_b_map13 | chr_allow_b_map34 | chr_allow_b_map66 | chr_allow_b_map71 | chr_allow_b_map77 | chr_allow_b_map78 | chr_allow_b_map79 | chr_allow_b_map89 | chr_allow_b_map107 | chr_allow_b_map165 | chr_allow_b_rambo1 | chr_allow_b_nesev;
assign irq_b = irq_b_mmc1 | irq_b_map28 | irq_b_map30 | irq_b_mmc2 | irq_b_mmc3 | irq_b_mmc4 | irq_b_map13 | irq_b_map34 | irq_b_map66 | irq_b_map71 | irq_b_map77 | irq_b_map78 | irq_b_map79 | irq_b_map89 | irq_b_map107 | irq_b_map165 | irq_b_rambo1 | irq_b_nesev;
wire [21:0] prg_addr_b = prg_addr_b_mmc1 | prg_addr_b_map28 | prg_addr_b_map30 | prg_addr_b_mmc2 | prg_addr_b_mmc3 | prg_addr_b_mmc4 | prg_addr_b_map13 | prg_addr_b_map34 | prg_addr_b_map66 | prg_addr_b_map71 | prg_addr_b_map77 | prg_addr_b_map78 | prg_addr_b_map79 | prg_addr_b_map89 | prg_addr_b_map107 | prg_addr_b_map165 | prg_addr_b_rambo1 | prg_addr_b_nesev;
wire [21:0] chr_addr_b = chr_addr_b_mmc1 | chr_addr_b_map28 | chr_addr_b_map30 | chr_addr_b_mmc2 | chr_addr_b_mmc3 | chr_addr_b_mmc4 | chr_addr_b_map13 | chr_addr_b_map34 | chr_addr_b_map66 | chr_addr_b_map71 | chr_addr_b_map77 | chr_addr_b_map78 | chr_addr_b_map79 | chr_addr_b_map89 | chr_addr_b_map107 | chr_addr_b_map165 | chr_addr_b_rambo1 | chr_addr_b_nesev;
assign flags_out_b = flags_out_b_mmc1 | flags_out_b_map28 | flags_out_b_map30 | flags_out_b_mmc2 | flags_out_b_mmc3 | flags_out_b_mmc4 | flags_out_b_map13 | flags_out_b_map34 | flags_out_b_map66 | flags_out_b_map71 | flags_out_b_map77 | flags_out_b_map78 | flags_out_b_map79 | flags_out_b_map89 | flags_out_b_map107 | flags_out_b_map165 | flags_out_b_rambo1 | flags_out_b_nesev;
wire [15:0] audio_out_b = audio_out_b_mmc1 | audio_out_b_map28 | audio_out_b_map30 | audio_out_b_mmc2 | audio_out_b_mmc3 | audio_out_b_mmc4 | audio_out_b_map13 | audio_out_b_map34 | audio_out_b_map66 | audio_out_b_map71 | audio_out_b_map77 | audio_out_b_map78 | audio_out_b_map79 | audio_out_b_map89 | audio_out_b_map107 | audio_out_b_map165 | audio_out_b_rambo1 | audio_out_b_nesev;
assign prg_dout_b = prg_dout_b_mmc1 & prg_dout_b_map28 & prg_dout_b_map30 & prg_dout_b_mmc2 & prg_dout_b_mmc3 & prg_dout_b_mmc4 & prg_dout_b_map13 & prg_dout_b_map34 & prg_dout_b_map66 & prg_dout_b_map71 & prg_dout_b_map77 & prg_dout_b_map78 & prg_dout_b_map79 & prg_dout_b_map89 & prg_dout_b_map107 & prg_dout_b_map165 & prg_dout_b_rambo1 & prg_dout_b_nesev;
assign chr_dout_b = chr_dout_b_map28;
// Game Bub: not built (Mapper 413 extra ROM, FDS disk sides, Mapper 16 EEPROM).
assign prg_aoute_m413 = 3'd0;
wire [1:0] fds_diskside = 2'd0;
wire map16_prg_write = 1'b0, map16_ovr = 1'b0;
wire [7:0] map16_data_out = 8'd0;
wire [17:0] map16_mapper_addr = 18'd0;


always @* begin
	me = 1024'd0;
	me[{flags[18:17],flags[7:0]}] = 1'b1;

	// Mapper output to cart pins
	{prg_aout[21:0], prg_allow,   chr_aout,   vram_a10,   vram_ce,   chr_allow,   prg_dout,   chr_dout,   irq,   audio} =
	{prg_addr_b,     prg_allow_b, chr_addr_b, vram_a10_b, vram_ce_b, chr_allow_b, prg_dout_b, chr_dout_b, irq_b, audio_out_b};

	// Currently only used for Mapper 16 EEPROM. Expand if needed.
	{mapper_addr, mapper_data_out, mapper_prg_write, mapper_ovr} = (me[159] | me[16]) ?
		{map16_mapper_addr, map16_data_out, map16_prg_write, map16_ovr} : 28'd0;
		
	// Currently only used for Mapper 413 Misc ROM. Expand if needed.
	prg_aout[24:22] = me[413] ? prg_aoute_m413 : 3'd0;

	{diskside} = {fds_diskside};

	// Behavior helper flags
	has_chr_dout    = flags_out_b[0];
	prg_bus_write   = flags_out_b[1];
	prg_conflict    = flags_out_b[2];
	has_savestate   = flags_out_b[3];
	prg_conflict_d0 = flags_out_b[4];
	has_flashsaves  = flags_out_b[5];

	// Address translation for SDRAM
	if ((prg_aout[21] == 1'b0) && (prg_aout[24] == 1'b0))
		prg_aout[20:0] = {(prg_aout[20:11] & prg_mask[9:0]), prg_aout[10:0]};

	if (chr_aout[21:20] == 2'b10)
		chr_aout[19:0] = {(chr_aout[19:11] & chr_mask[8:0]), chr_aout[10:0]};


	// Remap the CHR address into VRAM, if needed.
	chr_aout = vram_ce ? {11'b11_1010_0000_0, vram_a10, chr_ain[9:0]} : chr_aout;
	prg_aout = (prg_ain < 'h2000) ? {11'b11_1000_0000_0, prg_ain[10:0]} : prg_aout;
	prg_allow = prg_allow || (prg_ain < 'h2000);

	// The motherboard memories take priority over mapper-local RAM and
	// nametable wiring only while Vs. mode is selected. Explicitly suppress
	// mapper bus overrides so reads come from the selected SDRAM regions.
	if (vs_prg_ram) begin
		prg_aout = {3'b000, vs_prg_ram_addr};
		prg_allow = 1'b1;
		prg_bus_write = 1'b0;
		prg_conflict = 1'b0;
		prg_conflict_d0 = 1'b0;
	end

	if (vs_nametable_ram) begin
		chr_aout = vs_nametable_ram_addr;
		chr_allow = 1'b1;
		vram_ce = 1'b0;
		has_chr_dout = 1'b0;
	end
end

// savestates

assign SaveStateBus_Dout  = SaveStateBus_wired_or[0] | SaveStateBus_wired_or[1] | SaveStateBus_wired_or[2] | SaveStateBus_wired_or[3] | SaveStateBus_wired_or[4] | SaveStateBus_wired_or[5] | SaveStateBus_wired_or[6] | SaveStateBus_wired_or[7] | SaveStateBus_wired_or[8] | SaveStateBus_wired_or[9] | SaveStateBus_wired_or[10] | SaveStateBus_wired_or[11] | SaveStateBus_wired_or[12] | SaveStateBus_wired_or[13];

// Game Bub: no kept mapper has mapper-internal RAM in the save state.
assign Savestate_MAPRAMReadData = 8'h00;

endmodule
