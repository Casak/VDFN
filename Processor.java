package com.mts.rating.editor;

import com.mts.rating.component.*;
import com.mts.rating.migration.Migration;
import com.mts.rating.migration.MigrationException;
import com.mts.rating.migration.MigrationFactory;
import com.mts.rating.model.*;
import com.mts.rating.util.ResultCode;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import net.minidev.json.JSONObject;
import net.minidev.json.JSONValue;
import oracle.jdbc.OracleTypes;
import org.xml.sax.SAXException;

import javax.persistence.EntityManager;
import javax.persistence.StoredProcedureQuery;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

public class Processor {

	private final EntityManager entityManager = Main.getEntityManager();

	private BiConsumer<Integer,Integer> progressProperty;
	private Connection jdbcConnection;
	private Result result = new Result();
	private CallableStatement priceUpdateStatement;
	private CallableStatement updateTransitionStatement;
	private CallableStatement getPricesStatement;
	private CallableStatement isTimeSchemaEmptyStatement;
	private CallableStatement timeSchemaReplicateStatement;
	private CallableStatement addChangeRelationStatement;
	private CallableStatement changePriorityStatement;
	private List<TariffPlanChangeRelation> tariffPlanChangeRelations;

	public void setProgressProperty(BiConsumer progressProperty) {
		this.progressProperty = progressProperty;
	}

	public Result getResult() {
		return result;
	}

	private void start(String operationType, String operationDescription) {
		System.out.println("[Start: " + operationType + "] " + (operationDescription != null ? operationDescription : ""));
		Security.audit(operationType, operationDescription);
		entityManager.getTransaction().begin();
		StoredProcedureQuery query = entityManager.createNamedStoredProcedureQuery("Process.newOperation");
		query.setParameter("p_type", operationType);
		query.setParameter("p_user", Security.getCurrentUser());
		query.setParameter("p_description", operationDescription);
		query.execute();
	}

	private void finish() {
		entityManager.getTransaction().commit();
		System.out.println("[Finish]");
	}

	private boolean isTimeSchemaEmpty(TariffZone tariffZone, NetworkService networkService) {
		StoredProcedureQuery query = entityManager.createNamedStoredProcedureQuery("TimeSchema.isEmpty");
		query.setParameter("p_tariff_zone", tariffZone.getZoneId());
		query.setParameter("p_network_service", networkService == null ? null : networkService.getServiceId());
		query.execute();
		return Integer.valueOf(1).equals(query.getOutputParameterValue(1));
	}

	private boolean isTimeSchemaEmpty(TimeSchema timeSchema, LocalDateTime dateStart) {
		Integer result = null;
		try{
			jdbcConnection = entityManager.unwrap(Connection.class);
			isTimeSchemaEmptyStatement = jdbcConnection.prepareCall("{ call rdp.is_tzts_data_empty(?,?,?,?,?) }");
			isTimeSchemaEmptyStatement.registerOutParameter(1, OracleTypes.NUMBER);
			isTimeSchemaEmptyStatement.setInt(2, timeSchema.getTariffZoneId());
			isTimeSchemaEmptyStatement.setInt(3, timeSchema.getNetworkServiceId());
			isTimeSchemaEmptyStatement.setInt(4, timeSchema.getTariffPlanId());
			isTimeSchemaEmptyStatement.setTimestamp(5, Timestamp.valueOf(dateStart));
			isTimeSchemaEmptyStatement.execute();

			result = ((BigDecimal)isTimeSchemaEmptyStatement.getObject(1)).intValueExact();

		} catch (SQLException e) {
			e.printStackTrace();
		} catch (Throwable th) {
			th.printStackTrace();
		} finally {
			if (isTimeSchemaEmptyStatement != null) {
				try {
					isTimeSchemaEmptyStatement.close();

				} catch (SQLException ignore) {} finally {
					isTimeSchemaEmptyStatement = null;
				}
			}
		}

		return Integer.valueOf(1).equals(result);
	}

	private boolean isBilingServiceLinkEmpty(TariffZone tariffZone, NetworkService networkService) {
		StoredProcedureQuery query = entityManager.createNamedStoredProcedureQuery("BillingServiceLink.isEmpty");
		query.setParameter("p_tariff_zone", tariffZone.getZoneId());
		query.setParameter("p_network_service", networkService == null ? null : networkService.getServiceId());
		query.execute();
		return Integer.valueOf(1).equals(query.getOutputParameterValue(1));
	}

	private void copyTariffZone(TariffZone zoneSource, TariffZone zoneTarget, NetworkService networkService, LocalDateTime dateFrom, Boolean dateFuture, LocalDateTime dateStart) {
		StoredProcedureQuery query = entityManager.createNamedStoredProcedureQuery("TariffZone.copyPrice");
		query.setParameter("p_zone_source", zoneSource.getZoneId());
		query.setParameter("p_zone_target", zoneTarget.getZoneId());
		query.setParameter("p_network_service", networkService == null ? null : networkService.getServiceId());
		query.setParameter("p_date_from", Timestamp.valueOf(dateFrom));
		query.setParameter("p_date_future", Integer.valueOf(dateFuture ? 1 : 0));
		query.setParameter("p_date_start", Timestamp.valueOf(dateStart));
		query.execute();
	}

	public int  copyTariffZone(TariffZone zoneSource, TariffZone zoneTarget, List<NetworkService> networkServices, LocalDateTime dateFrom, Boolean dateFuture, LocalDateTime dateStart) {
		start("copyPrice", null);
		boolean bilingServiceLinkCheck = true;
		if (networkServices == null) {
			if (isTimeSchemaEmpty(zoneTarget, null)) {
				copyTariffZone(zoneSource, zoneTarget, (NetworkService) null, dateFrom, dateFuture, dateStart);
			} else {
				result.put(null, "Тарифное отношение для копирования цен должно быть пустым!");
			}
		} else {
			for (NetworkService networkService : networkServices) {
				if(isBilingServiceLinkEmpty(zoneTarget, networkService)){
					bilingServiceLinkCheck = false;
					result.put(networkService, "Не назначены связи с биллинговой услугой и ТО");
				}
			}

			if (bilingServiceLinkCheck) {
				for (NetworkService networkService : networkServices) {
                    if (isTimeSchemaEmpty(zoneTarget, networkService)) {
                        copyTariffZone(zoneSource, zoneTarget, networkService, dateFrom, dateFuture, dateStart);
                    } else {
                        result.put(networkService, "Тарифное отношение для копирования цен должно быть пустым!");
                    }
                }
			}

		}
		finish();
		return 0;
	}

	public int copyExtension(TariffZoneMember zoneSource, TariffZoneMember zoneTarget, LocalDateTime dateFrom, Boolean dateFuture, LocalDateTime dateStart) {
		start("copyExtension", null);
		StoredProcedureQuery query = entityManager.createNamedStoredProcedureQuery("TariffZone.copyExtension");
		query.setParameter("p_member_source", zoneSource.getZoneMemberId());
		query.setParameter("p_member_target", zoneTarget.getZoneMemberId());
		query.setParameter("p_date_from",     Timestamp.valueOf(dateFrom));
		query.setParameter("p_date_future",   Integer.valueOf(dateFuture ? 1 : 0));
		query.setParameter("p_date_start",    Timestamp.valueOf(dateStart));
		query.execute();
		Integer ret = (Integer) query.getOutputParameterValue(6);
		if (ret != 0) {
			result.put(zoneTarget, ResultCode.find(ret));
		}
		finish();
		return ret;
	}

	private int updateTransition(TariffPlanChangeRelation changeRelation, String counterCode, String function, Integer changeRules) throws SQLException {
		if (function == null || function.isEmpty()) {
			function = changeRelation.getTransitionDefault();
		}
		try {
			TransitionParameters transitionParameters = new TransitionParameters(changeRelation.getTransitionParameters());
			if (changeRules > 0) {
				String old = transitionParameters.getOutpar(counterCode);
				if (old != null && ! old.isEmpty()) {
					if (changeRules == 2) return 0;
					if (! (old.equals("oa") || old.equals("na"))) return 0;
				}
			}
			transitionParameters.addOutpar(counterCode, function);
			updateTransitionStatement.setInt(1, changeRelation.getTariffPlanChangeId());
			updateTransitionStatement.setString(2, transitionParameters.toXML());
			updateTransitionStatement.execute();
		} catch (ParserConfigurationException | SAXException | IOException e) {
			e.printStackTrace();
			return -122;
		}
		return 0;
	}

	private int updateTransition(List<TariffPlan> tariffPlans, Integer direction, String counterCode, LocalDateTime dateStart, String function, Integer changeRules) {
		try {
			jdbcConnection = entityManager.unwrap(Connection.class);
			updateTransitionStatement = jdbcConnection.prepareCall("{ call rdp.update_tpcr_params(?,?) }");
			for (TariffPlan tariffPlan : tariffPlans) {
				if (! result.containsKey(tariffPlan)) {
					StoredProcedureQuery query = entityManager.createNamedStoredProcedureQuery("TariffPlanChangeRelation.getByTPandCounter");
					query.setParameter("p_tariff_plan",  tariffPlan.getTariffPlanId());
					query.setParameter("p_direction",    direction);
					query.setParameter("p_counter_code", counterCode);
					query.setParameter("p_date_start",   Timestamp.valueOf(dateStart));
					List<TariffPlanChangeRelation> list = query.getResultList();
					for (TariffPlanChangeRelation changeRelation : list) {
						if (! tariffPlanChangeRelations.contains(changeRelation)) {
							updateTransition(changeRelation, counterCode, function, changeRules);
							tariffPlanChangeRelations.add(changeRelation);
						}
					}
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
			return -113;
		} finally {
			if (updateTransitionStatement != null) {
				try {
					updateTransitionStatement.close();
				} catch (SQLException ignore) {} finally {
					updateTransitionStatement = null;
				}
			}
		}
		return 0;
	}

	private int updateTransition(List<TariffPlan> tariffPlans, String counterCode, LocalDateTime dateStart, String functionFrom, String functionTo, Integer changeRules, Result<TariffPlan, ResultCode> result) {
		tariffPlanChangeRelations = new ArrayList();
		updateTransition(tariffPlans, 0, counterCode, dateStart, functionTo, changeRules);
		updateTransition(tariffPlans, 1, counterCode, dateStart, functionFrom, changeRules);
		tariffPlanChangeRelations = null;
		return 0;
	}

	private Integer addCounter(TariffPlan tariffPlan, String counterCode, String serviceCode, String serviceGroup, LocalDateTime dateStart) {
		StoredProcedureQuery query = entityManager.createNamedStoredProcedureQuery("TariffPlan.addCounter");
		query.setParameter("p_tariff_plan",   tariffPlan.getTariffPlanId());
		query.setParameter("p_counter_code",  counterCode);
		query.setParameter("p_service_code",  serviceCode);
		query.setParameter("p_service_group", serviceGroup);
		query.setParameter("p_date_start", Timestamp.valueOf(dateStart));
		query.execute();
		return (Integer) query.getOutputParameterValue(6);
	}

	public int addCounter(String counterCode, String serviceCode, String serviceGroup, List<TariffPlan> tariffPlans, LocalDateTime dateStart, String functionFrom, String functionTo, Integer changeRules) {
		String description = "counter='" + counterCode + "', " +
                             "dateStart='" + dateStart + "', " +
                             (serviceCode == null || serviceCode.isEmpty() ? "" : ("serviceCode='" + serviceCode + "', ")) +
		                     (functionFrom == null || functionFrom.isEmpty() ? "" : ("functionFrom='" + functionFrom + "', ")) +
				             (functionTo == null || functionTo.isEmpty() ? "" : ("functionTo='" + functionTo + "', ")) +
		                     "changeRules=" + changeRules;
		start("addCounter", description);
		for (TariffPlan tariffPlan : tariffPlans) {
			Integer ret = addCounter(tariffPlan, counterCode, serviceCode, serviceGroup, dateStart);
			if (ret != 0) {
                result.put(tariffPlan, ResultCode.find(ret));
				if (ret == -20102 || ret == -20103) {
					break;
				}
			}
		}
		updateTransition(tariffPlans, counterCode, dateStart, functionFrom, functionTo, changeRules, result);
		finish();
		return 0;
	}

	private void updatePrice(NetworkServicePrice price, LocalDateTime dateStart, String description, ComponentParameters componentParameters, String change, boolean replaceLog) throws SQLException {
		priceUpdateStatement.setInt(1, price.getPriceId());
		priceUpdateStatement.setTimestamp(2, dateStart != null ? Timestamp.valueOf(dateStart) : null);
		priceUpdateStatement.setString(3, description);
		priceUpdateStatement.setString(4, componentParameters.serialize());
		priceUpdateStatement.setString(5, componentParameters.toString());
		priceUpdateStatement.setString(6, change);
		priceUpdateStatement.setInt(7, replaceLog ? 1 : 0);
		priceUpdateStatement.execute();
	}

	private int updatePrice(NetworkServicePrice price, LocalDateTime dateStart, JSONObject change) throws SQLException {
		String description = "RD-MultiEditor";
		ComponentParameters parameters = RatingComponentFactory.createComponentParameters(price.getProgramId());
		parameters.parseObject(price.getComponentParameters());
		String parametersBefore = parameters.serialize();
		parameters.update(change);
		String parametersAfter = parameters.serialize();
		updatePrice(price, dateStart, description, parameters, change.toJSONString(), false);
		if(change != null && change.size() >= 0 && parametersBefore.equals(parametersAfter)){
			result.put("ТК = " + price.getComponentParametersId(), "Цена не была изменена");
		}
		if (dateStart == null) {
			price.setComponentParameters(parameters.toString());
			price.setComponentUnparsed(parameters.serialize());
		} else {
			price.setEndDate(Timestamp.valueOf(dateStart.minusSeconds(1)));
		}
		return 0;
	}

	public int updatePrice(List<NetworkServicePrice> prices, LocalDateTime dateStart, JSONObject params) {
		if (prices.isEmpty()) return 0;
		NetworkServicePrice first = prices.get(0);
		ComponentParametersMapper mapper = RatingComponentFactory.createComponentParametersMapper(first.getProgramId());
		JSONObject change = mapper.decode(params);
		start("updatePrice", null);
		try {
			jdbcConnection = entityManager.unwrap(Connection.class);
			priceUpdateStatement = jdbcConnection.prepareCall("{ call rdp.update_price(?,?,?,?,?,?,?) }");
			int count = 0;
			int total = prices.size();
			for (NetworkServicePrice price : prices) {
				if(price.getProgramId().equals("PriceTypeExtension")){
					ComponentParameters priceParameters = RatingComponentFactory.createComponentParameters(price.getProgramId());
					priceParameters.parseObject(price.getComponentParameters());
					JSONObject basicNonCall = (JSONObject) priceParameters.getObject().get("Basic:Non Call Services");
					if((basicNonCall.containsKey("Version") && !basicNonCall.get("Version").equals("VER:01"))
							|| (basicNonCall.containsKey("PricePerFact") && basicNonCall.get("PricePerFact") != null && !basicNonCall.get("PricePerFact").equals(""))){
						Iterator it = change.entrySet().iterator();
						while (it.hasNext())
						{
							Map.Entry<String,Object> entry = (Map.Entry<String,Object>) it.next();
							if(entry.getKey().contains("DynamicPrice")) it.remove();
						}
					}
				}
				updatePrice(price, dateStart, change);
				progressProperty.accept(++count, total);
			}
		} catch (SQLException e) {
			e.printStackTrace();
			return -113;
		} catch (Throwable th) { 
			th.printStackTrace();
			return -100;
		} finally {
			if (priceUpdateStatement != null) {
				try {
					priceUpdateStatement.close();
				} catch (SQLException ignore) {} finally {
					priceUpdateStatement = null;
				}
			}
			finish();
		}
		return 0;
	}

	private Integer splitTimeSchema(Integer ratingRule, Timestamp dateStartNew, Integer componentNew) {
		CallableStatement splitTimeSchemaStatement = null;
		Integer newRuleId = null;
		try {
			jdbcConnection = entityManager.unwrap(Connection.class);
			splitTimeSchemaStatement = jdbcConnection.prepareCall("{ call rdp.split_time_schema(?,?,?,?) }");
			splitTimeSchemaStatement.setInt(1, ratingRule);
			splitTimeSchemaStatement.setTimestamp(2, dateStartNew);
			if (componentNew != null) {
				splitTimeSchemaStatement.setInt(3, componentNew);
			} else {
				splitTimeSchemaStatement.setNull(3, Types.NUMERIC);
			}
			splitTimeSchemaStatement.registerOutParameter(4, Types.NUMERIC);
			splitTimeSchemaStatement.execute();
			newRuleId = splitTimeSchemaStatement.getInt(4);
		} catch (SQLException e) {
			e.printStackTrace();
		} catch (Throwable th) {
			th.printStackTrace();
		} finally {
			if (splitTimeSchemaStatement != null) {
				try {
					splitTimeSchemaStatement.close();
				} catch (SQLException ignore) {} finally {
					splitTimeSchemaStatement = null;
				}
			}
		}
		return newRuleId;
	}

	private Integer splitTimeSchema(TimeSchema timeSchema, LocalDateTime dateStart, Integer componentNew) {
		Integer timeSchemaNew = splitTimeSchema(timeSchema.getRatingRuleId(), Timestamp.valueOf(dateStart), componentNew);
		timeSchema.endDateProperty().set(Timestamp.valueOf(dateStart.minusSeconds(1)));
		return timeSchemaNew;
	}

	public int splitTimeSchema(List<TimeSchema> timeSchemaList, LocalDateTime dateStart) {
		start("splitTimeSchema", null);
		timeSchemaList.forEach(timeSchema -> splitTimeSchema(timeSchema, dateStart, null));
		finish();
		return 0;
	}

	private boolean isTimeSchemaEmpty(Integer tariffZoneId, Integer networkServiceId, Integer tariffPlanId, LocalDateTime dateStart) {
		StoredProcedureQuery query = entityManager.createNamedStoredProcedureQuery("TimeSchema.isEmptyForReplication");
		query.setParameter("p_tariff_zone", tariffZoneId);
		query.setParameter("p_network_service", networkServiceId);
		query.setParameter("p_tariff_plan", tariffPlanId);
		query.setParameter("p_date_start", Timestamp.valueOf(dateStart));
		query.execute();
		return Integer.valueOf(1).equals(query.getOutputParameterValue(1));
	}

	private void replicateTimeSchema(TimeSchema timeSchema, LocalDateTime dateStart, TariffPlan tariffPlan) throws SQLException {
		timeSchemaReplicateStatement.setInt(1, timeSchema.getRatingRuleId());
		timeSchemaReplicateStatement.setTimestamp(2, Timestamp.valueOf(dateStart));
		timeSchemaReplicateStatement.setInt(3, tariffPlan.getTariffPlanId());
		timeSchemaReplicateStatement.execute();
	}

	public int replicateTimeSchema(List<TimeSchema> timeSchemaList, LocalDateTime dateStart, List<TariffPlan> tariffPlans) {
		start("replicateTimeSchema", null);
		try {
			jdbcConnection = entityManager.unwrap(Connection.class);
			timeSchemaReplicateStatement = jdbcConnection.prepareCall("{ call rdp.replicate_time_schema(?,?,?) }");
			for (TariffPlan tariffPlan : tariffPlans) {
				for (TimeSchema timeSchema : timeSchemaList) {
					if (timeSchema.getTariffPlanId() != tariffPlan.getTariffPlanId()) {
						if (isTimeSchemaEmpty(timeSchema.getTariffZoneId(), timeSchema.getNetworkServiceId(), tariffPlan.getTariffPlanId(), dateStart)) {
							replicateTimeSchema(timeSchema, dateStart, tariffPlan);
						} else {
							result.put(tariffPlan, "Тарифный план уже содержит тарифные правила для выбранных ТО");
						}
					} else {
						result.put(tariffPlan, "Исходный и целевой тарифные планы совпадают");
					}
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
			return -113;
		} finally {
			if (timeSchemaReplicateStatement != null) {
				try {
					timeSchemaReplicateStatement.close();
				} catch (SQLException ignore) {} finally {
					timeSchemaReplicateStatement = null;
				}
			}
			finish();
		}
		return 0;
	}

	private TransitionParameters generateTransitions(TariffPlan tariffPlanOld, TariffPlan tariffPlanNew, LocalDateTime dateStart) {
		StoredProcedureQuery query = entityManager.createNamedStoredProcedureQuery("TariffPlanChangeRelation.genTransitionParameters");
		query.setParameter("p_tariff_plan_old", tariffPlanOld.getTariffPlanId());
		query.setParameter("p_tariff_plan_new", tariffPlanNew.getTariffPlanId());
		query.setParameter("p_date_start", Timestamp.valueOf(dateStart));
		List<TransitionOutpar> transitions = query.getResultList();
		return new TransitionParameters(transitions);
	}

	private void addChangeRelation(TariffPlan tariffPlanOld, TariffPlan tariffPlanNew, String transitionParameters, String serviceCode, LocalDateTime dateStart, Integer calcMethod, String channelBan, String serviceBan) throws SQLException {
		String description = "RD-MultiEditor [" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + "]";
		addChangeRelationStatement.setInt(1, tariffPlanOld.getTariffPlanId());
		addChangeRelationStatement.setInt(2, tariffPlanNew.getTariffPlanId());
		addChangeRelationStatement.setString(3, transitionParameters);
		addChangeRelationStatement.setString(4, serviceCode);
		addChangeRelationStatement.setTimestamp(5, Timestamp.valueOf(dateStart));
		addChangeRelationStatement.setString(6, description);
		addChangeRelationStatement.setInt(7, calcMethod != null ? calcMethod.intValue() : 0);
		addChangeRelationStatement.setString(8, channelBan);
		addChangeRelationStatement.setString(9, serviceBan);
		addChangeRelationStatement.execute();
	}

	private void addChangeRelation(TariffPlan tariffPlanOld, TariffPlan tariffPlanNew, String serviceCode, LocalDateTime dateStart, Integer calcMethod, String channelBan, String serviceBan) throws SQLException {
		TransitionParameters transitionParameters = generateTransitions(tariffPlanOld, tariffPlanNew, dateStart);
		addChangeRelation(tariffPlanOld, tariffPlanNew, transitionParameters.toXML(), serviceCode, dateStart, calcMethod, channelBan, serviceBan);
	}

	public int addChangeRelation(TariffPlan tariffPlanOld, List<TariffPlan> tariffPlansTo, String serviceCode, LocalDateTime dateStart, Integer calcMethod, int changeDirection, String channelBan, String serviceBan) {
		String description = "tariffPlanFrom=" + tariffPlanOld.getTariffPlanId() + ", " +
                             "service='" + serviceCode + "', " +
                             "dateStart='" + dateStart + "', " +
                             "changeDirection=" + changeDirection;
		start("addChangeRelation", description);
		try {
			jdbcConnection = entityManager.unwrap(Connection.class);
			addChangeRelationStatement = jdbcConnection.prepareCall("{ call rdp.add_change_relation(?,?,?,?,?,?,?,?,?) }");
			for (TariffPlan tariffPlanNew : tariffPlansTo) {
				if (! tariffPlanOld.equals(tariffPlanNew)) {
					if (changeDirection == 0 || changeDirection == 2) {
						addChangeRelation(tariffPlanOld, tariffPlanNew, serviceCode, dateStart, calcMethod, channelBan, serviceBan);
					}
					if (changeDirection == 1 || changeDirection == 2) {
						addChangeRelation(tariffPlanNew, tariffPlanOld, serviceCode, dateStart, calcMethod, channelBan, serviceBan);
					}
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
			return -113;
		} finally {
			if (addChangeRelationStatement != null) {
				try {
					addChangeRelationStatement.close();
				} catch (SQLException ignore) {} finally {
					addChangeRelationStatement = null;
				}
			}
			finish();
		}
		return 0;
	}

	private int changePriority(TariffZoneMember zone, Long priority, LocalDateTime dateStart) throws SQLException {
		changePriorityStatement.setInt(1, zone.getZoneMemberId());
		changePriorityStatement.setInt(2, priority.intValue());
		changePriorityStatement.setTimestamp(3, Timestamp.valueOf(dateStart));
		changePriorityStatement.registerOutParameter(4, Types.INTEGER);
		changePriorityStatement.execute();
		return changePriorityStatement.getInt(4);
	}

	public int changePriority(List<TariffZoneMember> zones, Long priority, LocalDateTime dateStart) {
		String description = "priority=" + priority;
		start("changePriority", description);
		try {
			jdbcConnection = entityManager.unwrap(Connection.class);
			changePriorityStatement = jdbcConnection.prepareCall("{ call rdp.change_priority(?,?,?,?) }");
			for (TariffZoneMember zone : zones) {
				int ret = changePriority(zone, priority, dateStart);
				if (ret != 0) {
					result.put(zone, ResultCode.find(ret));
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
			return -113;
		} finally {
			if (changePriorityStatement != null) {
				try {
					changePriorityStatement.close();
				} catch (SQLException ignore) {} finally {
					changePriorityStatement = null;
				}
			}
			finish();
		}
		return 0;
	}

	private Integer assignTariffZone(Integer callingGroup, Integer calledGroup, Integer tariffZone, LocalDateTime dateStart, TariffZoneRule rule, Integer extension, Long priority) {
		StoredProcedureQuery query = entityManager.createNamedStoredProcedureQuery("TariffZoneMember.assign");
		query.setParameter("p_calling_group", callingGroup);
		query.setParameter("p_called_group", calledGroup);
		query.setParameter("p_tariff_zone", tariffZone);
		query.setParameter("p_date_start", Timestamp.valueOf(dateStart));
		query.setParameter("p_tariff_zone_rule_id", rule.getRuleId());
		query.setParameter("p_extension", extension);
		query.setParameter("p_priority", priority);
		query.execute();
		return (Integer) query.getOutputParameterValue(8);
	}

	private TariffZoneRule findTariffZoneRule(String tariffZoneName) {
		StoredProcedureQuery query = entityManager.createNamedStoredProcedureQuery("TariffZoneRule.findByName");
		if(tariffZoneName == null) tariffZoneName = "";
		query.setParameter("p_tariff_zone_rule_name", tariffZoneName);
		ObservableList<TariffZoneRule> list = FXCollections.observableArrayList(query.getResultList());
		query.execute();
		TariffZoneRule rule = new TariffZoneRule(null, "");
		if(list.size() == 1) {
			rule = list.get(0);
		}
		return rule;
	}

	public int assignTariffZone(List<TariffZoneMember> zoneMembers, TariffZone tariffZone, LocalDateTime dateStart, TariffZoneExtension extension, Long priority) {
		String description = "tariffZone=" + tariffZone.getZoneId();
		start("assignTariffZone", description);
		for (TariffZoneMember zoneMember : zoneMembers) {
			Integer ret = assignTariffZone(zoneMember.getCallingGroupId(), zoneMember.getCalledGroupId(), tariffZone.getZoneId(), dateStart, findTariffZoneRule(zoneMember.getRule()), extension != null ? extension.getRuleId() : null, extension != null ? priority : null);
			if (ret != 0) {
				if(ret == ResultCode.TARIFF_ZONE_ASSIGNED.getResultCode())
				result.put(new Object() {
					@Override
					public String toString() {
						return zoneMember.getCallingGroup() + " -> " + zoneMember.getCalledGroup();
					}
				}, ResultCode.find(ret));
				if(ret == ResultCode.ZONE_EXTENSION_ASSIGNED.getResultCode())
					result.put(new Object() {
					@Override
					public String toString() {
						return zoneMember.getCallingGroup() + " -> " + zoneMember.getCalledGroup() + " -> " + extension.getRuleName();
					}
				}, ResultCode.find(ret));
				if(ret == ResultCode.DUPLICATE_DATA_ERROR.getResultCode())
					result.put(new Object() {
					@Override
					public String toString() {
						return zoneMember.getCallingGroup() + " -> " + zoneMember.getCalledGroup() + " -> " + zoneMember.getRule() + " -> " + zoneMember.getExtension();
					}
				}, ResultCode.find(ret));

			}
		}
		finish();
		return 0;
	}

	private List<NetworkServicePrice> getNetworkServicePrices(Integer timeSchemaNew) {
		List<NetworkServicePrice> list = new ArrayList<>();
		ResultSet set = null;
		try{
			jdbcConnection = entityManager.unwrap(Connection.class);
			getPricesStatement = jdbcConnection.prepareCall("{ call rdm.get_prices_for_schema(?,?) }");
			getPricesStatement.registerOutParameter(1, OracleTypes.CURSOR);
			getPricesStatement.setInt(2, timeSchemaNew);
			getPricesStatement.execute();
			set = (ResultSet) getPricesStatement.getObject(1);

			while(set.next()){
				Integer priceID =  bigDecimalToInteger((BigDecimal) set.getObject("price_id"));
				Integer ratingRuleID = bigDecimalToInteger((BigDecimal) set.getObject("rating_rule_id"));
				Integer networkServiceID = bigDecimalToInteger((BigDecimal) set.getObject("network_service_id"));
				Integer tariffZoneID = bigDecimalToInteger((BigDecimal) set.getObject("tariff_zone_id"));
				Integer tariffPlanID = bigDecimalToInteger((BigDecimal) set.getObject("tariff_plan_id"));
				Integer location = bigDecimalToInteger((BigDecimal) set.getObject("location"));
				Integer billingServiceID = bigDecimalToInteger((BigDecimal) set.getObject("billing_service_id"));
				Integer trafficPeriodID = bigDecimalToInteger((BigDecimal) set.getObject("traffic_period_id"));
				Integer componentID = bigDecimalToInteger((BigDecimal) set.getObject("component_id"));
				Integer component_parameters_id = bigDecimalToInteger((BigDecimal) set.getObject("component_parameters_id"));

				String zoneCode = getStringFromResultSet((String) set.getObject("zone_code"));
				String billingServiceName = getStringFromResultSet((String) set.getObject("billing_service_name"));
				String programID = getStringFromResultSet((String) set.getObject("program_id"));
				String networkServiceName = getStringFromResultSet((String) set.getObject("network_service_name"));
				String tariffZoneName = getStringFromResultSet((String) set.getObject("tariff_zone_name"));
				String tariffPlanName= getStringFromResultSet((String) set.getObject("tariff_plan_name"));
				String ratingComponentName= getStringFromResultSet((String) set.getObject("rating_component_name"));
				String trafficPeriodName= getStringFromResultSet((String) set.getObject("traffic_period_name"));

				String componentParameters = clobToString(set.getClob("component_parameters"));

				Timestamp startDate = getTimeStampFromResultSet((Timestamp) set.getObject("start_date"));
				Timestamp endDate = getTimeStampFromResultSet((Timestamp) set.getObject("end_date"));

				NetworkServicePrice price = new NetworkServicePrice(priceID, ratingRuleID, networkServiceID,
						tariffZoneID, tariffPlanID, startDate, endDate,
						location, billingServiceID, billingServiceName, trafficPeriodID,
						zoneCode, programID, networkServiceName, tariffZoneName,
						tariffPlanName, componentID, ratingComponentName, trafficPeriodName,
						component_parameters_id, componentParameters);

				list.add(price);
			}

		} catch (SQLException e) {
			e.printStackTrace();
		} catch (Throwable th) {
			th.printStackTrace();
		} finally {
			if (getPricesStatement != null) {
				try {
					getPricesStatement.close();

				} catch (SQLException ignore) {} finally {
					getPricesStatement = null;
				}
			}
			if (set != null) {
				try {
					set.close();

				} catch (SQLException ignore) {} finally {
					set = null;
				}
			}
		}
			return list;
	}

	private String clobToString(Clob data) {
		StringBuilder sb = new StringBuilder();
		try {
			if (null != data) {
				Reader reader = data.getCharacterStream();
				BufferedReader br = new BufferedReader(reader);

				String line;
				while(null != (line = br.readLine())) {
					sb.append(line);
				}
				br.close();
			}
		} catch (SQLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return sb.toString();
	}

	private Integer bigDecimalToInteger(BigDecimal data) {
		Integer result;
		if(null != data){
				result = data.intValueExact();
			}
		else {
			result = null;
		}
		return result;
	}

	private Timestamp getTimeStampFromResultSet(Timestamp data) {
		Timestamp result;
		if(null != data){
				result = data;
			}
		else {
			result = null;
		}
		return result;
	}

	private String getStringFromResultSet(String data) {
		String result;
		if(null == data || data.isEmpty() ){
				result = "";
			}
		else {
			result = data;
		}
		return result;
	}



	private String checkAllowed(Migration service, List<NetworkServicePrice> list) {
		for (NetworkServicePrice price : list) {
			String cp = price.getComponentParameters();
			if (cp == null || cp.isEmpty()) continue;
			String result = service.checkAllowed((JSONObject) JSONValue.parse(cp));
			if (result != null) return result;
		}
		return null;
	}

	private int migrateTimeSchema(Migration service, Integer componentNew, TimeSchema timeSchema, LocalDateTime dateStart) throws SQLException {
		String description = "RD-MultiEditor";
		Integer timeSchemaNew = splitTimeSchema(timeSchema, dateStart, componentNew);
		List<NetworkServicePrice> list = getNetworkServicePrices(timeSchemaNew);
		if (! list.isEmpty()) {
			ComponentParameters parameters = RatingComponentFactory.createComponentParameters(list.get(0).getProgramId());
			for (NetworkServicePrice price : list) {
				String cp = price.getComponentParameters();
				if (cp == null || cp.isEmpty()) continue;
				try {
					parameters.setObject(service.migrateData((JSONObject) JSONValue.parse(cp)));
					updatePrice(price, null, description, parameters, null, true);
				} catch (MigrationException e) {
					e.printStackTrace();
					result.put(price, e.getMessage());
				}
			}
		}
		return 0;
	}


	public int migrateTimeSchema(List<TimeSchema> timeSchemaList, String programId, LocalDateTime dateStart) {
		start("migrateTimeSchema", programId);
		String sourceId = timeSchemaList.get(0).getProgramId();
		Migration service = MigrationFactory.createMigrationService(sourceId, programId);
		Integer componentNew = RatingComponentEnum.find(programId).getComponentId();
		try {
			jdbcConnection = entityManager.unwrap(Connection.class);
			priceUpdateStatement = jdbcConnection.prepareCall("{ call rdp.update_price(?,?,?,?,?,?,?) }");
			for (TimeSchema timeSchema : timeSchemaList) {
				List<NetworkServicePrice> list = getNetworkServicePrices(timeSchema.getRatingRuleId());
				if (!list.isEmpty()) {
				String checkResult = checkAllowed(service, list);
					if (checkResult != null) {
					result.put(timeSchema, checkResult);
					continue;
				}
				}
				if (!isTimeSchemaEmpty(timeSchema, dateStart)) {
					result.put(timeSchema, "Цена на выбранную дату уже назначена");
				}else{
					Integer ret = migrateTimeSchema(service, componentNew, timeSchema, dateStart);
					if (ret != 0) {
						result.put(timeSchema, "Ошибка при миграции параметров ТК");
					}
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
			return -113;
		} catch (Exception ex) {
			ex.printStackTrace();
		} finally {
			if (priceUpdateStatement != null) {
				try {
					priceUpdateStatement.close();
				} catch (SQLException ignore) {} finally {
					priceUpdateStatement = null;
				}
			}
			finish();
		}
		return 0;
	}

}
