import { computed, ref, toValue, watch, type MaybeRefOrGetter } from 'vue'
import { fetchCategoryTree } from '@/api/asset'
import type { AssetCategoryVO } from '@/types/asset'

/**
 * 项目分类树（一级 + 二级）。
 *
 * 抽成 composable 的原因：资产列表的筛选器、新建资产表单、分类维护抽屉、
 * 详情页的「改分类」四处都要这棵树，各自 `fetchCategoryTree` 会变成
 * 打开一个页面发四次相同的请求。这里每个调用方一份自己的缓存，
 * 谁改了分类谁负责 `reload()`（分类的增删改只发生在抽屉里）。
 */
export function useCategoryTree(projectId: MaybeRefOrGetter<number>) {
  const tree = ref<AssetCategoryVO[]>([])
  const loading = ref(false)
  const loaded = ref(false)

  async function load(force = false) {
    const id = toValue(projectId)
    if (!Number.isFinite(id) || id <= 0) {
      return
    }
    if (loaded.value && !force) {
      return
    }
    loading.value = true
    try {
      tree.value = await fetchCategoryTree(id)
      loaded.value = true
    } catch {
      // 拦截器已提示；这里保持空树，页面其余部分仍可渲染
    } finally {
      loading.value = false
    }
  }

  /** 分类被增删改后调用，强制重取 */
  function reload() {
    return load(true)
  }

  // 切换项目必须重取：分类是项目级的，留着上一个项目的树会让编号前缀和
  // 分类名都错位。loaded 一并清掉，避免 watch 里被 load 的早退挡住
  watch(
    () => toValue(projectId),
    () => {
      loaded.value = false
      tree.value = []
    }
  )

  /** 一级分类与二级分类拍平成一个列表，元素带上完整路径名 */
  const flat = computed(() => flattenCategories(tree.value))

  return { tree, flat, loading, loaded, load, reload }
}

export interface FlatCategory {
  id: number
  name: string
  code: string
  color: string | null
  /** 一级分类自身时等于 name，二级分类时形如「角色 / 主角模型」 */
  fullName: string
  /** 0 = 一级，1 = 二级 */
  level: number
  assetCount: number
}

/** 把分类树拍平成下拉框能直接用的列表，二级分类带上一级前缀避免重名歧义 */
export function flattenCategories(tree: AssetCategoryVO[]): FlatCategory[] {
  const result: FlatCategory[] = []
  for (const top of tree) {
    result.push({
      id: top.id,
      name: top.name,
      code: top.code,
      color: top.color,
      fullName: top.name,
      level: 0,
      assetCount: top.assetCount
    })
    for (const child of top.children ?? []) {
      result.push({
        id: child.id,
        name: child.name,
        code: child.code,
        color: child.color,
        fullName: `${top.name} / ${child.name}`,
        level: 1,
        assetCount: child.assetCount
      })
    }
  }
  return result
}

/** 按分类ID取全路径名，用于表格行与详情页展示；取不到时回落为空串 */
export function categoryFullName(tree: AssetCategoryVO[], categoryId: number | null | undefined): string {
  if (categoryId == null) {
    return ''
  }
  const hit = flattenCategories(tree).find((c) => c.id === categoryId)
  return hit?.fullName ?? ''
}
