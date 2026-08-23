package com.mawai.wiibsim.campaign.model;

/**
 * 一个标的在<b>投票目标日</b>（明天那个 UTC 日）的票况。
 *
 * @param symbol      标的代码，不带展示名——卡片标题跟界面语言，由前端查 {@code market:coinName.*}
 * @param myDirection 我投的方向；null = 这一天还没投这个标的
 */
public record VoteBoard(String symbol, long upCount, long downCount, String myDirection) {
}
