package com.mawai.wiibservice.agent.quant.factor;

import com.mawai.wiibservice.agent.quant.domain.AgentVote;
import com.mawai.wiibservice.agent.quant.domain.FeatureSnapshot;

import java.util.List;

public interface FactorAgent {

    String name();

    List<AgentVote> evaluate(FeatureSnapshot snapshot);
}
