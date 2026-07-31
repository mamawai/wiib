package com.mawai.wiibsim.campaign.model;

/**
 * 一个标的在<b>投票目标日</b>（明天那个 UTC 日）的票况。
 *
 * @param myDirection 我投的方向；null = 这一天还没投这个标的
 */
public record VoteBoard(String symbol, String label, long upCount, long downCount, String myDirection) {
}
