package com.dre.brewery;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrewingStand;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;

/**
 * Updated for 1.9 to replicate the "Brewing" process for distilling.
 * Because of how metadata has changed, the brewer no longer triggers as previously described.
 * So, I've added some event tracking and manual forcing of the brewing "animation" if the
 *  set of ingredients in the brewer can be distilled.
 * Nothing here should interfere with vanilla brewing.
 *
 * @author ProgrammerDan (1.9 distillation update only)
 */
public class BDistiller {

	private static final int DISTILLTIME = 400;
	private static Map<Block, BDistiller> trackedDistillers = new HashMap<>();

	private int taskId;
	private int runTime = -1;
	private int brewTime = -1;
	private Block standBlock;
	private int fuel;

	public BDistiller(Block standBlock, int fuel) {
		this.standBlock = standBlock;
		this.fuel = fuel;
	}

	public void cancelDistill() {
		Bukkit.getScheduler().cancelTask(taskId); // cancel prior
	}

	public void start() {
		taskId = new DistillRunnable().runTaskTimer(P.p, 2L, 1L).getTaskId();
	}

	public static void distillerClick(InventoryClickEvent event) {
		BrewerInventory standInv = (BrewerInventory) event.getInventory();
		final Block standBlock = standInv.getHolder().getBlock();

		// If we were already tracking the brewer, cancel any ongoing event due to the click.
		BDistiller distiller = trackedDistillers.get(standBlock);
		if (distiller != null) {
			distiller.cancelDistill();
			standInv.getHolder().setBrewingTime(0); // Fixes brewing continuing without fuel for normal potions
			standInv.getHolder().update();
		}
		final int fuel = standInv.getHolder().getFuelLevel();

		// Now check if we should bother to track it.
		trackedDistillers.put(standBlock, new BDistiller(standBlock, fuel)).start();
	}

	// Returns a Brew or null for every Slot in the BrewerInventory
	public static Brew[] getDistillContents(BrewerInventory inv) {
		ItemStack item;
		Brew[] contents = new Brew[3];
		for (int slot = 0; slot < 3; slot++) {
			item = inv.getItem(slot);
			if (item != null) {
				contents[slot] = Brew.get(item);
			}
		}
		return contents;
	}

	public static byte hasBrew(BrewerInventory brewer) {
		ItemStack item = brewer.getItem(3); // ingredient
		boolean glowstone = (item != null && Material.GLOWSTONE_DUST == item.getType()); // need dust in the top slot.
		byte customFound = 0;
		for (Brew brew : getDistillContents(brewer)) {
			if (brew != null) {
				if (!glowstone) {
					return 1;
				}
				if (brew.canDistill()) {
					return 2;
				} else {
					customFound = 1;
				}
			}
		}
		return customFound;
	}

	public static boolean runDistill(BrewerInventory inv) {
		boolean custom = false;
		Brew[] contents = getDistillContents(inv);
		for (int slot = 0; slot < 3; slot++) {
			if (contents[slot] == null) continue;
			if (contents[slot].canDistill()) {
				// is further distillable
				custom = true;
			} else {
				contents[slot] = null;
			}
		}
		if (custom) {
			Brew.distillAll(inv, contents);
			return true;
		}
		return false;
	}

	public static int getLongestDistillTime(BrewerInventory inv) {
		int bestTime = 0;
		int time;
		Brew[] contents = getDistillContents(inv);
		for (int slot = 0; slot < 3; slot++) {
			if (contents[slot] == null) continue;
			time = contents[slot].getDistillTimeNextRun();
			if (time == 0) {
				// Undefined Potion needs 40 seconds
				time = 800;
			}
			if (time > bestTime) {
				bestTime = time;
			}
		}
		if (bestTime > 0) {
			return bestTime;
		}
		return 800;
	}

	public class DistillRunnable extends BukkitRunnable {

		@Override
		public void run() {
			BlockState now = standBlock.getState();
			if (now instanceof BrewingStand) {
				BrewingStand stand = (BrewingStand) now;
				if (brewTime == -1) { // only check at the beginning (and end) for distillables
					switch (hasBrew(stand.getInventory())) {
						case 1:
							// Custom potion but not for distilling. Stop any brewing and cancel this task
							if (stand.getBrewingTime() > 0) {
								if (P.use1_11) {
									// The trick below doesnt work in 1.11, but we dont need it anymore
									// This should only happen with older Brews that have been made with the old Potion Color System
									stand.setBrewingTime(Short.MAX_VALUE);
								} else {
									// Brewing time is sent and stored as short
									// This sends a negative short value to the Client
									// In the client the Brewer will look like it is not doing anything
									stand.setBrewingTime(Short.MAX_VALUE << 1);
								}
								stand.setFuelLevel(fuel);
								stand.update();
							}
						case 0:
							// No custom potion, cancel and ignore
							this.cancel();
							trackedDistillers.remove(standBlock);
							P.p.debugLog("nothing to distill");
							return;
						default:
							runTime = getLongestDistillTime(stand.getInventory());
							brewTime = runTime;
							P.p.debugLog("using brewtime: " + runTime);

					}
				}

				brewTime--; // count down.
				stand.setBrewingTime((int) ((float) brewTime / ((float) runTime / (float) DISTILLTIME)) + 1);

				if (brewTime <= 1) { // Done!
					stand.setBrewingTime(0);
					stand.update();
					BrewerInventory brewer = stand.getInventory();
					if (!runDistill(brewer)) {
						this.cancel();
						trackedDistillers.remove(standBlock);
						P.p.debugLog("All done distilling");
					} else {
						brewTime = -1; // go again.
						P.p.debugLog("Can distill more! Continuing.");
					}
				} else {
					stand.update();
				}
			} else {
				this.cancel();
				trackedDistillers.remove(standBlock);
				P.p.debugLog("The block was replaced; not a brewing stand.");
			}
		}
	}
}