package com.provectus.kafka.ui.util;

import com.provectus.kafka.ui.model.JmxConnectionInfo;
import com.provectus.kafka.ui.model.MetricDTO;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.KeyedObjectPool;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class JmxClusterUtil {

  private static final String JMX_URL = "service:jmx:rmi:///jndi/rmi://";
  private static final String JMX_SERVICE_TYPE = "jmxrmi";
  private static final String KAFKA_SERVER_PARAM = "kafka.server";
  private static final String NAME_METRIC_FIELD = "name";
  private final KeyedObjectPool<JmxConnectionInfo, JMXConnector> pool;

  @SneakyThrows
  public List<MetricDTO> getJmxMetrics(String host, int port, boolean jmxSsl,
                                    @Nullable String username, @Nullable String password) {
    String jmxUrl = JMX_URL + host + ":" + port + "/" + JMX_SERVICE_TYPE;
    final var connectionInfo = JmxConnectionInfo.builder()
            .url(jmxUrl)
            .ssl(jmxSsl)
            .username(username)
            .password(password)
            .build();
    JMXConnector srv;
    try {
      srv = pool.borrowObject(connectionInfo);
    } catch (Exception e) {
      log.error("Cannot get JMX connector for the pool due to: ", e);
      return Collections.emptyList();
    }

    List<MetricDTO> result = new ArrayList<>();
    try {
      MBeanServerConnection msc = srv.getMBeanServerConnection();
      var jmxMetrics = msc.queryNames(null, null).stream()
          .filter(q -> q.getCanonicalName().startsWith(KAFKA_SERVER_PARAM))
          .collect(Collectors.toList());
      for (ObjectName jmxMetric : jmxMetrics) {
        final Hashtable<String, String> params = jmxMetric.getKeyPropertyList();
        MetricDTO metric = new MetricDTO();
        metric.setName(params.get(NAME_METRIC_FIELD));
        metric.setCanonicalName(jmxMetric.getCanonicalName());
        metric.setParams(params);
        metric.setValue(getJmxMetric(jmxMetric.getCanonicalName(), msc));
        result.add(metric);
      }
      pool.returnObject(connectionInfo, srv);
    } catch (Exception e) {
      log.error("Cannot get jmxMetricsNames, {}", jmxUrl, e);
      closeConnectionExceptionally(jmxUrl, srv);
    }
    return result;
  }


  @SneakyThrows
  private Map<String, BigDecimal> getJmxMetric(String canonicalName, MBeanServerConnection msc) {
    Map<String, BigDecimal> resultAttr = new HashMap<>();
    ObjectName name = new ObjectName(canonicalName);
    var attrNames = msc.getMBeanInfo(name).getAttributes();
    for (MBeanAttributeInfo attrName : attrNames) {
      var value = msc.getAttribute(name, attrName.getName());
      if (NumberUtil.isNumeric(value)) {
        resultAttr.put(attrName.getName(), new BigDecimal(value.toString()));
      }
    }
    return resultAttr;
  }

  private void closeConnectionExceptionally(String url, JMXConnector srv) {
    try {
      pool.invalidateObject(new JmxConnectionInfo(url), srv);
    } catch (Exception e) {
      log.error("Cannot invalidate object in pool, {}", url);
    }
  }

  public MetricDTO reduceJmxMetrics(MetricDTO metric1, MetricDTO metric2) {
    var result = new MetricDTO();
    Map<String, BigDecimal> value = Stream.concat(
        metric1.getValue().entrySet().stream(),
        metric2.getValue().entrySet().stream()
    ).collect(Collectors.groupingBy(
        Map.Entry::getKey,
        Collectors.reducing(BigDecimal.ZERO, Map.Entry::getValue, BigDecimal::add)
    ));
    result.setName(metric1.getName());
    result.setCanonicalName(metric1.getCanonicalName());
    result.setParams(metric1.getParams());
    result.setValue(value);
    return result;
  }

  private boolean isWellKnownMetric(MetricDTO metric) {
    final Optional<String> param =
        Optional.ofNullable(metric.getParams().get(NAME_METRIC_FIELD)).filter(p ->
            Arrays.stream(JmxMetricsName.values()).map(Enum::name)
                .anyMatch(n -> n.equals(p))
        );
    return metric.getCanonicalName().contains(KAFKA_SERVER_PARAM) && param.isPresent();
  }
}
