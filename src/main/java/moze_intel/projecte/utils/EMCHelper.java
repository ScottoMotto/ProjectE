package moze_intel.projecte.utils;

import com.google.common.collect.Maps;
import moze_intel.projecte.api.item.IItemEmc;
import moze_intel.projecte.config.ProjectEConfig;
import moze_intel.projecte.emc.EMCMapper;
import moze_intel.projecte.emc.FuelMapper;
import moze_intel.projecte.emc.SimpleStack;
import moze_intel.projecte.utils.Constants;
import net.minecraft.block.Block;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Helper class for EMC.
 * Notice: Please try to keep methods tidy and alphabetically ordered. Thanks!
 */
public final class EMCHelper
{
	/**
	 * Consumes EMC from fuel items or Klein Stars
	 * Any extra EMC is discarded !!! To retain remainder EMC use ItemPE.consumeFuel()
	 */
	public static double consumePlayerFuel(EntityPlayer player, double minFuel)
	{
		if (player.capabilities.isCreativeMode)
		{
			return minFuel;
		}

		IItemHandler inv = player.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.UP);
		Map<Integer, Integer> map = new LinkedHashMap<>();
		boolean metRequirement = false;
		int emcConsumed = 0;

		for (int i = 0; i < inv.getSlots(); i++)
		{
			ItemStack stack = inv.getStackInSlot(i);

			if (stack.isEmpty())
			{
				continue;
			}
			else if (stack.getItem() instanceof IItemEmc)
			{
				IItemEmc itemEmc = ((IItemEmc) stack.getItem());
				if (itemEmc.getStoredEmc(stack) >= minFuel)
				{
					itemEmc.extractEmc(stack, minFuel);
					player.inventoryContainer.detectAndSendChanges();
					return minFuel;
				}
			}
			else if (!metRequirement)
			{
				if(FuelMapper.isStackFuel(stack))
				{
					long emc = getEmcValue(stack);
					int toRemove = ((int) Math.ceil((minFuel - emcConsumed) / (double) emc));

					if (stack.getCount() >= toRemove)
					{
						map.put(i, toRemove);
						emcConsumed += emc * toRemove;
						metRequirement = true;
					}
					else
					{
						map.put(i, stack.getCount());
						emcConsumed += emc * stack.getCount();

						if (emcConsumed >= minFuel)
						{
							metRequirement = true;
						}
					}

				}
			}
		}

		if (metRequirement)
		{
			for (Map.Entry<Integer, Integer> entry : map.entrySet())
			{
				inv.extractItem(entry.getKey(), entry.getValue(), false);
			}

			player.inventoryContainer.detectAndSendChanges();
			return emcConsumed;
		}

		return -1;
	}

	public static boolean doesBlockHaveEmc(Block block)
	{
		return block != null && doesItemHaveEmc(new ItemStack(block));
	}

	public static boolean doesItemHaveEmc(ItemStack stack)
	{
		if (stack.isEmpty())
		{
			return false;
		}

		SimpleStack iStack = new SimpleStack(stack);

		if (!iStack.isValid())
		{
			return false;
		}

		if (ItemHelper.isDamageable(stack))
		{
			iStack = iStack.withMeta(0);
		}

		return EMCMapper.mapContains(iStack);
	}

	public static boolean doesItemHaveEmc(Item item)
	{
		return item != null && doesItemHaveEmc(new ItemStack(item));
	}

	public static long getEmcValue(Block block)
	{
		SimpleStack stack = new SimpleStack(new ItemStack(block));

		if (stack.isValid() && EMCMapper.mapContains(stack))
		{
			return EMCMapper.getEmcValue(stack);
		}

		return 0;
	}

	public static long getEmcValue(Item item)
	{
		SimpleStack stack = new SimpleStack(new ItemStack(item));

		if (stack.isValid() && EMCMapper.mapContains(stack))
		{
			return EMCMapper.getEmcValue(stack);
		}

		return 0;
	}

	/**
	 * Does not consider stack size
	 */
	public static long getEmcValue(ItemStack stack)
	{
		if (stack.isEmpty())
		{
			return 0;
		}

		SimpleStack iStack = new SimpleStack(stack);

		if (!iStack.isValid())
		{
			return 0;
		}

		if (!EMCMapper.mapContains(iStack) && ItemHelper.isDamageable(stack))
		{
			//We don't have an emc value for id:metadata, so lets check if we have a value for id:0 and apply a damage multiplier based on that emc value.
			iStack = iStack.withMeta(0);

			if (EMCMapper.mapContains(iStack))
			{
				long emc = EMCMapper.getEmcValue(iStack);

				// maxDmg + 1 because vanilla lets you use the tool one more time
				// when item damage == max damage (shows as Durability: 0 / max)
				int relDamage = (stack.getMaxDamage() + 1 - stack.getItemDamage());

				if (relDamage <= 0)
				{
					// This may happen when mods overflow their max damage or item damage.
					// Don't use durability or enchants for emc calculation if this happens.
					return emc;
				}

				long result = emc * relDamage;

				if (result <= 0)
				{
					//Congratulations, big number is big.
					return emc;
				}

				result /= stack.getMaxDamage();
				boolean positive = result > 0;
				result += getEnchantEmcBonus(stack);

				//If it was positive and then became negative that means it overflowed
				if (positive && result < 0) {
					return emc;
				}

				positive = result > 0;
				result += getStoredEMCBonus(stack);

				//If it was positive and then became negative that means it overflowed
				if (positive && result < 0) {
					return emc;
				}

				if (result <= 0)
				{
					return 1;
				}

				return result;
			}
		}
		else
		{
			if (EMCMapper.mapContains(iStack))
			{
				return EMCMapper.getEmcValue(iStack) + getEnchantEmcBonus(stack) + (long)getStoredEMCBonus(stack);
			}
		}

		return 0;
	}

	private static int getEnchantEmcBonus(ItemStack stack)
	{
		int result = 0;

		Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments(stack);

		if (!enchants.isEmpty())
		{
			for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet())
			{
				Enchantment ench = entry.getKey();
				if (ench == null || ench.getRarity().getWeight() == 0)
				{
					continue;
				}

				result += Constants.ENCH_EMC_BONUS / ench.getRarity().getWeight() * entry.getValue();
			}
		}

		return result;
	}

	public static long getEmcSellValue(ItemStack stack)
	{
		double originalValue = EMCHelper.getEmcValue(stack);

		if (originalValue == 0)
		{
			return 0;
		}

		long emc = (long) Math.floor(originalValue * EMCMapper.covalenceLoss);

		if (emc < 1)
		{
			emc = 1;
		}

		return emc;
	}

	public static String getEmcSellString(ItemStack stack, int stackSize)
	{
		if (EMCMapper.covalenceLoss == 1.0)
		{
			return " ";
		}

		long emc = EMCHelper.getEmcSellValue(stack);

		return " (" + Constants.EMC_FORMATTER.format((emc * stackSize)) + ")";
	}

	public static int getKleinStarMaxEmc(ItemStack stack)
	{
		return Constants.MAX_KLEIN_EMC[stack.getItemDamage()];
	}

	private static double getStoredEMCBonus(ItemStack stack) {
		if (stack.getTagCompound() != null && stack.getTagCompound().hasKey("StoredEMC")) {
			return stack.getTagCompound().getDouble("StoredEMC");
		} else if (stack.getItem() instanceof IItemEmc) {
			return ((IItemEmc) stack.getItem()).getStoredEmc(stack);
		}
		return 0;
	}

	public static int getEMCPerDurability(ItemStack stack){

		int emc;

		if(stack == null)
			return 0;

		ItemStack stackCopy = stack.copy();
		stackCopy.setItemDamage(0);

		if(ItemHelper.isItemRepairable(stack)){
			emc = (int)Math.ceil(EMCHelper.getEmcValue(stackCopy) / stack.getMaxDamage());
			return emc > 1 ? emc : 1;
		}
		return 1;
	}
}
