package org.ovirt.engine.core.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang.StringUtils;
import org.ovirt.engine.core.common.businessentities.NumaNodeStatistics;
import org.ovirt.engine.core.common.businessentities.VdsNumaNode;
import org.ovirt.engine.core.common.utils.Pair;
import org.ovirt.engine.core.compat.Guid;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

@Named
@Singleton
public class VdsNumaNodeDaoImpl extends NumaNodeDaoImpl<VdsNumaNode> implements VdsNumaNodeDao {

    @Override
    public List<VdsNumaNode> getAllVdsNumaNodeByVdsId(Guid vdsId) {
        MapSqlParameterSource parameterSource = getCustomMapSqlParameterSource()
                .addValue("vds_id", vdsId);

        List<VdsNumaNode> vdsNumaNodes =
                getCallsHandler().executeReadList("GetNumaNodeByVdsId",
                        vdsNumaNodeRowMapper, parameterSource);

        List<Pair<Guid, Integer>> numaNodesCpus =
                getCallsHandler().executeReadList("GetNumaNodeCpuByVdsId",
                        vdsNumaNodeCpusRowMapper, parameterSource);

        Map<Guid, List<Integer>> numaNodesCpusMap = new HashMap<>();

        for (Pair<Guid, Integer> pair : numaNodesCpus) {
            if (!numaNodesCpusMap.containsKey(pair.getFirst())) {
                numaNodesCpusMap.put(pair.getFirst(), new ArrayList<>());
            }
            numaNodesCpusMap.get(pair.getFirst()).add(pair.getSecond());
        }

        for (VdsNumaNode node : vdsNumaNodes) {
            if (numaNodesCpusMap.containsKey(node.getId())) {
                node.setCpuIds(numaNodesCpusMap.get(node.getId()));
            }
        }

        return vdsNumaNodes;
    }

    private static final RowMapper<VdsNumaNode> vdsNumaNodeRowMapper =
            new RowMapper<VdsNumaNode>() {
                @Override
                public VdsNumaNode mapRow(ResultSet rs, int rowNum)
                        throws SQLException {
                    VdsNumaNode entity = new VdsNumaNode();
                    NumaNodeStatistics stat = new NumaNodeStatistics();
                    entity.setId(getGuid(rs, "numa_node_id"));
                    entity.setIndex(rs.getInt("numa_node_index"));
                    entity.setMemTotal(rs.getLong("mem_total"));
                    stat.setMemFree(rs.getLong("mem_free"));
                    stat.setMemUsagePercent(rs.getInt("usage_mem_percent"));
                    stat.setCpuSys(rs.getDouble("cpu_sys"));
                    stat.setCpuUser(rs.getDouble("cpu_user"));
                    stat.setCpuIdle(rs.getDouble("cpu_idle"));
                    stat.setCpuUsagePercent(rs.getInt("usage_cpu_percent"));
                    entity.setNumaNodeStatistics(stat);
                    entity.setNumaNodeDistances(getDistanceMap(rs.getString("distance")));
                    return entity;
                }
            };

    private static final RowMapper<Pair<Guid, Integer>> vdsNumaNodeCpusRowMapper =
            new RowMapper<Pair<Guid, Integer>>() {
                @Override
                public Pair<Guid, Integer> mapRow(ResultSet rs, int rowNum)
                        throws SQLException {
                    return new Pair<>(getGuid(rs, "numa_node_id"), rs.getInt("cpu_core_id"));
                }
            };

    // format: (<index_id>, <distance>);*, for example: "0, 10; 2, 16"
    private static Map<Integer, Integer> getDistanceMap(String distance) {
        Map<Integer, Integer> nodeDistance = new HashMap<>();
        if (StringUtils.isBlank(distance)) {
            return nodeDistance;
        }
        String[] distanceArray = distance.split(";");
        for (int i = 0; i < distanceArray.length; i++) {
            String[] nodeDistanceArray = distanceArray[i].split(",");
            nodeDistance.put(Integer.valueOf(nodeDistanceArray[0]), Integer.valueOf(nodeDistanceArray[1]));
        }
        return nodeDistance;
    }
}
