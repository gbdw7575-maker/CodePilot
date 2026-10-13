package com.dsh.evals.model;

/**
 * 评测任务类别（MA-11，AT-18 四类任务，ADR §9）：
 * <ul>
 *   <li>SIMPLE：单文档/单文件简单修复（INLINE 即可完成，无必要委派）；</li>
 *   <li>INDEPENDENT：≥2 个写范围不相交的独立缺陷（可并行）；</li>
 *   <li>DEPENDENT：上游产物→下游实现的强依赖链（下游不得先于上游启动）；</li>
 *   <li>CONFLICT：两个写者改同一文件，预期汇合冲突整批不应用。</li>
 * </ul>
 */
public enum EvalCategory {
    SIMPLE,
    INDEPENDENT,
    DEPENDENT,
    CONFLICT
}
