package me.herry.minecraftAI.ai.crafting;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 서버에 등록된 레시피(Bukkit Recipe API)를 읽어서 제작 순서를 계산하고 실제로 제작한다.
 * 레시피를 코드에 직접 적어 두지 않으므로 데이터팩이나 버전에 따라 레시피가 달라져도 그대로 동작한다.
 */
public final class CraftingSystem {
    /**
     * 제작 한 단계.
     *
     * @param times      레시피를 반복할 횟수
     * @param needsTable 3x3 제작 칸이 필요해서 작업대가 있어야 하는지
     */
    public record CraftStep(Recipe recipe, Material result, int times, boolean needsTable) {
    }

    /**
     * 목표 아이템을 만들기 위한 제작 순서. 재료가 부족하면 missing 에 부족한 재료 후보가 담긴다.
     */
    public record CraftPlan(List<CraftStep> steps, Set<Material> missing) {
        public boolean isFeasible() {
            return missing.isEmpty() && !steps.isEmpty();
        }

        public boolean needsTable() {
            for (CraftStep step : steps) {
                if (step.needsTable()) return true;
            }
            return false;
        }
    }

    private record Ingredient(RecipeChoice choice, int count) {
    }

    // 원목 -> 판자 -> 막대기 -> 도구 정도의 깊이면 충분하다. 레시피가 서로 물고 도는 경우를 막는 역할도 한다.
    private static final int MAX_DEPTH = 4;
    private static final int STORAGE_SIZE = 36;

    // 결과물별 제작 레시피. 처음 쓸 때 만든다.
    private @Nullable Map<Material, List<Recipe>> recipeIndex;

    // 데이터팩을 다시 불러와서 레시피가 바뀌었을 때 호출한다.
    public void clearCache() {
        recipeIndex = null;
    }

    /**
     * 현재 가진 재료(stock)로 target 을 amount 개 만드는 순서를 계산한다. stock 은 변경하지 않는다.
     * 계산 중에 stock 을 여러 번 복사하므로, 가진 아이템 종류만큼만 자리를 차지하는 HashMap 을 쓴다.
     */
    public CraftPlan plan(Material target, int amount, Map<Material, Integer> stock) {
        List<CraftStep> steps = new ArrayList<>();
        Set<Material> missing = EnumSet.noneOf(Material.class);
        resolve(target, amount, new HashMap<>(stock), steps, missing, 0, EnumSet.noneOf(Material.class));
        return new CraftPlan(steps, missing);
    }

    /**
     * 한 단계를 실제로 제작한다. 재료가 모자라면 아무것도 하지 않고 false 를 반환한다.
     */
    public boolean craft(Player player, CraftStep step) {
        PlayerInventory inventory = player.getInventory();
        List<Ingredient> ingredients = ingredientsOf(step.recipe());
        for (Ingredient ingredient : ingredients) {
            if (countMatching(inventory, ingredient.choice()) < ingredient.count() * step.times()) return false;
        }

        for (Ingredient ingredient : ingredients) {
            removeMatching(inventory, ingredient.choice(), ingredient.count() * step.times());
        }
        for (int i = 0; i < step.times(); i++) {
            Map<Integer, ItemStack> leftover = inventory.addItem(step.recipe().getResult().clone());
            // 인벤토리가 가득 차면 남은 결과물은 발밑에 떨어뜨린다.
            for (ItemStack item : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
        }
        return true;
    }

    /**
     * 제작에 성공하면 true. 실패하면 부족한 재료를 missing 에 추가한다.
     *
     * @param expanding 지금 만들려고 따져 보는 중인 아이템들 (자기 자신, 그리고 위 단계에서 함께 후보로 놓인 것들)
     */
    private boolean resolve(Material target, int amount, Map<Material, Integer> stock, List<CraftStep> steps, Set<Material> missing,
                            int depth, Set<Material> expanding) {
        if (depth > MAX_DEPTH) return false;
        boolean entered = expanding.add(target);
        try {
            return resolveAny(target, amount, stock, steps, missing, depth, expanding);
        } finally {
            if (entered) expanding.remove(target);
        }
    }

    private boolean resolveAny(Material target, int amount, Map<Material, Integer> stock, List<CraftStep> steps, Set<Material> missing,
                               int depth, Set<Material> expanding) {
        for (Recipe recipe : recipesFor(target)) {
            Map<Material, Integer> trialStock = new HashMap<>(stock);
            List<CraftStep> trialSteps = new ArrayList<>();
            Set<Material> trialMissing = EnumSet.noneOf(Material.class);
            if (resolveRecipe(recipe, target, amount, trialStock, trialSteps, trialMissing, depth, expanding)) {
                stock.clear();
                stock.putAll(trialStock);
                steps.addAll(trialSteps);
                // 앞의 레시피가 실패하면서 적어 둔 "부족한 재료"를 지운다. 지우지 않으면 뒤의 레시피로 만들 수 있는데도
                // 만들 수 없는 것으로 판정된다 (흰 침대처럼 염색 레시피가 먼저 나오는 아이템에서 생기던 문제).
                missing.clear();
                return true;
            }
            // 여러 레시피가 모두 실패하면 첫 번째(기본) 레시피에서 부족했던 재료를 알려 준다.
            if (missing.isEmpty()) missing.addAll(trialMissing);
        }
        return false;
    }

    private boolean resolveRecipe(Recipe recipe, Material target, int amount, Map<Material, Integer> stock, List<CraftStep> steps,
                                  Set<Material> missing, int depth, Set<Material> expanding) {
        int perCraft = Math.max(1, recipe.getResult().getAmount());
        int times = (amount + perCraft - 1) / perCraft;

        boolean satisfied = true;
        for (Ingredient ingredient : ingredientsOf(recipe)) {
            int need = ingredient.count() * times;
            need -= takeFromStock(stock, ingredient.choice(), need);
            if (need > 0 && !craftIngredient(ingredient.choice(), need, stock, steps, depth, expanding)) {
                missing.addAll(materialsOf(ingredient.choice()));
                satisfied = false;
            }
        }
        if (!satisfied) return false;

        steps.add(new CraftStep(recipe, target, times, needsTable(recipe)));
        stock.merge(target, perCraft * times, Integer::sum);
        return true;
    }

    /**
     * 부족한 재료를 다시 제작해서 채운다 (예: 막대기가 없으면 판자로, 판자가 없으면 원목으로).
     *
     * 이미 따져 보는 중인 아이템(expanding)은 후보에서 뺀다. "아무 침대 + 염료 = 다른 색 침대" 같은 염색 레시피는
     * 재료 후보가 결과물의 형제들이라서, 빼지 않으면 침대 -> 침대 -> 침대 ... 로 후보가 곱해져 한 번의 계산에
     * 수백 밀리초가 걸린다 (/ai perf 로 확인된 문제).
     */
    private boolean craftIngredient(RecipeChoice choice, int need, Map<Material, Integer> stock, List<CraftStep> steps, int depth,
                                    Set<Material> expanding) {
        List<Material> candidates = materialsOf(choice);
        // 이 후보들을 따져 보는 동안에는, 그 안쪽에서 같은 후보들을 다시 재료로 삼지 못하게 표시해 둔다.
        Set<Material> opened = EnumSet.noneOf(Material.class);
        for (Material candidate : candidates) {
            if (expanding.add(candidate)) opened.add(candidate);
        }
        try {
            for (Material candidate : candidates) {
                if (!opened.contains(candidate) || recipesFor(candidate).isEmpty()) continue;

                Map<Material, Integer> trialStock = new HashMap<>(stock);
                List<CraftStep> trialSteps = new ArrayList<>();
                if (!resolve(candidate, need, trialStock, trialSteps, EnumSet.noneOf(Material.class), depth + 1, expanding)) continue;

                int available = trialStock.getOrDefault(candidate, 0);
                if (available < need) continue;
                trialStock.put(candidate, available - need);
                stock.clear();
                stock.putAll(trialStock);
                steps.addAll(trialSteps);
                return true;
            }
            return false;
        } finally {
            expanding.removeAll(opened);
        }
    }

    private static int takeFromStock(Map<Material, Integer> stock, RecipeChoice choice, int need) {
        int taken = 0;
        for (Material material : materialsOf(choice)) {
            if (taken >= need) break;
            int available = stock.getOrDefault(material, 0);
            if (available <= 0) continue;
            int use = Math.min(available, need - taken);
            stock.put(material, available - use);
            taken += use;
        }
        return taken;
    }

    private List<Recipe> recipesFor(Material material) {
        if (recipeIndex == null) recipeIndex = buildIndex();
        return recipeIndex.getOrDefault(material, List.of());
    }

    /**
     * 서버의 모든 제작 레시피를 한 번만 훑어서 결과물별로 묶어 둔다.
     * 결과물 하나를 물을 때마다 Bukkit.getRecipesFor 를 부르면 그때마다 서버가 모든 레시피를 다시 변환해서,
     * 재료 열몇 가지만 따져도 수백 밀리초가 걸린다 (/ai perf 로 확인된 문제).
     */
    private static Map<Material, List<Recipe>> buildIndex() {
        Map<Material, List<Recipe>> index = new EnumMap<>(Material.class);
        Iterator<Recipe> recipes = Bukkit.recipeIterator();
        while (recipes.hasNext()) {
            Recipe recipe = recipes.next();
            if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe)) continue;
            index.computeIfAbsent(recipe.getResult().getType(), key -> new ArrayList<>()).add(recipe);
        }
        return index;
    }

    // 첫 판단 때 서버가 멈칫하지 않도록, 플러그인이 켜질 때 미리 레시피를 읽어 둔다.
    public void warmUp() {
        if (recipeIndex == null) recipeIndex = buildIndex();
    }

    // 같은 재료가 여러 칸에 들어가는 경우 하나로 합쳐서 필요한 개수를 센다.
    private static List<Ingredient> ingredientsOf(Recipe recipe) {
        Map<RecipeChoice, Integer> counts = new HashMap<>();
        if (recipe instanceof ShapedRecipe shaped) {
            Map<Character, RecipeChoice> choiceMap = shaped.getChoiceMap();
            for (String row : shaped.getShape()) {
                for (char key : row.toCharArray()) {
                    RecipeChoice choice = choiceMap.get(key);
                    if (choice != null) counts.merge(choice, 1, Integer::sum);
                }
            }
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            for (RecipeChoice choice : shapeless.getChoiceList()) {
                if (choice != null) counts.merge(choice, 1, Integer::sum);
            }
        }

        List<Ingredient> ingredients = new ArrayList<>(counts.size());
        counts.forEach((choice, count) -> ingredients.add(new Ingredient(choice, count)));
        return ingredients;
    }

    private static List<Material> materialsOf(RecipeChoice choice) {
        if (choice instanceof RecipeChoice.MaterialChoice materialChoice) return materialChoice.getChoices();
        if (choice instanceof RecipeChoice.ExactChoice exactChoice) {
            List<Material> materials = new ArrayList<>();
            for (ItemStack item : exactChoice.getChoices()) materials.add(item.getType());
            return materials;
        }
        return List.of();
    }

    private static boolean needsTable(Recipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            String[] shape = shaped.getShape();
            if (shape.length > 2) return true;
            for (String row : shape) {
                if (row.length() > 2) return true;
            }
            return false;
        }
        return recipe instanceof ShapelessRecipe shapeless && shapeless.getChoiceList().size() > 4;
    }

    private static int countMatching(PlayerInventory inventory, RecipeChoice choice) {
        int total = 0;
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.isEmpty() && choice.test(item)) total += item.getAmount();
        }
        return total;
    }

    private static void removeMatching(PlayerInventory inventory, RecipeChoice choice, int amount) {
        for (int slot = 0; slot < STORAGE_SIZE && amount > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty() || !choice.test(item)) continue;
            int take = Math.min(item.getAmount(), amount);
            amount -= take;
            if (take >= item.getAmount()) inventory.setItem(slot, null);
            else item.setAmount(item.getAmount() - take);
        }
    }
}
