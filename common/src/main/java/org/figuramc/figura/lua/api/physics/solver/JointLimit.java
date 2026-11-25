package org.figuramc.figura.lua.api.physics.solver;

/**
 * 관절 범위(swing-twist) — addJoint 가 부를 때 값을 복사한다(뒤에 바꿔도 안 먹는다).
 * <ul>
 * <li>axisA — A 국소 원뿔 가운데 축 · axisB — B 국소 뼈(비틀림) 축</li>
 * <li>refA · refB — 비틀림 0 기준(각자 축에 수직 · 아니면 수직 성분만 쓴다)</li>
 * <li>swing1Deg — 뼈가 refA 쪽으로 기우는 반각 · swing2Deg — axisA × refA 쪽으로 기우는 반각(타원 원뿔 · 1 ~ 179 로 자른다)</li>
 * <li>twistMinDeg ~ twistMaxDeg — 뼈 축 둘레 비틀림 범위(−179 ~ 179)</li>
 * </ul>
 */
public final class JointLimit {
    public double[] axisA, axisB, refA, refB;
    public double swing1Deg, swing2Deg, twistMinDeg, twistMaxDeg;
}
