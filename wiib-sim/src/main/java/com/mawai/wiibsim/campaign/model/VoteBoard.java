package com.mawai.wiibsim.campaign.model;

/**
 * 一个标的的当日票况。
 *
 * @param myDirection 我投的方向；null = 今天还没投这个标的
 */
public record VoteBoard(String symbol, String label, long upCount, long downCount, String myDirection) {
}
