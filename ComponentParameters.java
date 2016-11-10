package com.mts.rating.component;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.mts.rating.migration.DefaultObject;
import com.mts.rating.migration.DefaultObjectService;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import net.minidev.json.JSONValue;

import java.util.List;
import java.util.Map.Entry;

public abstract class ComponentParameters {

	public final static String DELETE_FLAG = "#DELETE";

	private String program;
	private JSONObject object;

	public ComponentParameters(String program) {
		this.program = program;
	}

	public JSONObject getObject() {
		return object;
	}

	public void setObject(JSONObject object) {
		this.object = object;
	}

	public void parseObject(String source) {
		this.object = (JSONObject) JSONValue.parse(source);
	}

	public String serialize() {
		return RatingComponentFactory.createRatingComponent(program).serialize(object);
	}

	@Override
	public String toString() {
		return object.toString();
	}

	protected Object getByKey(String key) {
		Object result;
		try {
			result = JsonPath.read(object, "$." + key);
		} catch (PathNotFoundException e) {
			result = null;
		}
		return result;
	}

	private JSONObject get(String key) {
		if (key == null || key.isEmpty()) {
			return object;
		}
		Object result = getByKey(key);
		if (result == null) {
			return null;
		} else if (result instanceof List) {
			List list = (List) result;
			if (list.size() > 0) {
				result = list.get(0);
			} else {
				result = null;
			}
		}
		return (JSONObject) result;
	}

	protected JSONArray getArray(String key) {
		if (key == null || key.isEmpty()) {
			return null;
		}
		JSONObject object = get(getParent(key));
		if (object == null) {
			return null;
		}
		return (JSONArray) object.get(getName(key));
	}

	protected String getParent(String key) {
		int pos = key.lastIndexOf(".");
		return pos == -1 ? "" : key.substring(0, pos);
	}

	protected String getName(String key) {
		int pos = key.lastIndexOf(".");
		return pos == -1 ? key : key.substring(pos + 1);
	}

	protected void updateSimple(String parent, String name, Object value) {
		JSONObject param = get(parent);
		if (param == null || isIncomplete(parent, param)) {
			param = (JSONObject) initialize(parent);
			if (param == null) return;
		}
		param.put(name, value);
	}

	protected boolean isIncomplete(String key, JSONObject param) {
		return false;
	}

	protected boolean useQuantity() {
		return (Boolean) getByKey("['Basic:Non Call Services'].UseQuantity");
	}

	private void updateSimple(String key, Object value) {
		if (key.endsWith("PricePerUnit") && program.equals("PriceTypeExtension")) {
			if (useQuantity()) {
				key = key.replaceAll("PricePerUnit", "Price.PricePerUnit.Price");
			} else {
				key = key.replaceAll("PricePerUnit", "Price.PricePerFact");
			}
		}
		if (key.endsWith("].Unit") && program.equals("PriceTypeExtension")) {
			if (useQuantity()) {
				key = key.replaceAll("Unit$", "Price.PricePerUnit.Unit");
			}
		}
		updateSimple(getParent(key), getName(key), value);
	}

	private void update(String key, Object value) {
		if (key.endsWith("Counters.Counters") || key.endsWith(".Counters")) {
			addCounter(key, (JSONObject) value);
		} else if (key.endsWith("SpecialPrice.Price") || key.endsWith(".SpecialPrice")) {
			addSpecialPrice(key, (JSONObject) value);
		} else if (key.endsWith("Discounts.Discounts") || key.endsWith("Discounts.NormalDiscount.Discounts") || key.endsWith(".Discounts")) {
			addDiscount(key, (JSONObject) value);
		} else if (key.endsWith("DynamicPrice.Thresholds") || key.endsWith("DynamicPrice.Price.Thresholds")) {
			addDynamicThreshold(key, (JSONObject) value);
		} else if (key.endsWith("StepPrice.Thresholds") || key.endsWith("StepPrice.Price.Thresholds")) {
			addStepThreshold(key, (JSONObject) value);
		} else if (key.endsWith("StepPrice.ActivationService") || key.endsWith("StepPrice.Price.ActivationService")) {
			updateSimple(key, value);
			updateSimple("['Basic:Non Call Services'].Param39", value);
			updateSimple("['FF:Non Call Services'].Param39", value);
		} else if (key.endsWith(DELETE_FLAG)) {
			delete(key.replace(DELETE_FLAG, ""), (String)value);
		} else {
			updateSimple(key, value);
		}
	}

	private void delete(String key, String value) {
		if (key.contains("Counters.Counters")) {
			deleteCounter(key, value);
		} else if (key.contains("Discounts.Discounts")) {
			deleteDiscount(key, value);
		} else if (key.contains("SpecialPrice.Price")) {
			deleteSpecialPrice(key, value);
		}
	}

	public void update(JSONObject change) {
		for (Entry<String,Object> entry : change.entrySet()) {
			update(entry.getKey(), entry.getValue());
		}
		if (!isDefaultValueToClobExist(object, "Basic:Non Call Services")) {
			addDefaultValue("Basic:Non Call Services");
            }
		if (!isDefaultValueToClobExist(object, "FF:Non Call Services")) {
			addDefaultValue("FF:Non Call Services");
            }
	}

	protected boolean isDefaultValueToClobExist(JSONObject object, String type){
		return true;
	}

	private JSONObject newFirstEvent() {
		JSONObject result = new JSONObject();
		result.put("CounterCode", "");
		result.put("CounterType", Long.valueOf(0));
		result.put("ActivationService", "");
		result.put("SetupFee", Double.valueOf(0));
		return result;
	}

	protected Object initialize(String key) {
		String parent = getParent(key);
		String name = getName(key);
		JSONObject result = null;
		switch (name) {
		case "FirstEvent":
			if (program.equals("ItemCharging") && parent.isEmpty()) {
				result = new JSONObject();
				result.put("SetupFeeOnStepCounter", Boolean.FALSE);
				result.put("SetupFeeOnZeroPrice", Boolean.FALSE);
				result.put("UseFirstEvent", Boolean.FALSE);
				result.put("FirstEvent", newFirstEvent());
			} else {
				result = newFirstEvent();
			}
			break;
		}
		if (result != null) {
			updateSimple(parent, name, result);
			if (program.equals("ItemCharging") && key.equals("FirstEvent.FirstEvent") && ! object.containsKey("Tab14")) {
				updateSimple("", "Tab14", "");
			}
		}
		return result;
	}

	private void addCounter(String key, JSONObject addCounterData) {
		Integer position = (Integer) addCounterData.get("Position");
		String beforeCounter = (String) addCounterData.get("BeforeCounter");
		JSONObject counter = decodeCounter((JSONObject) addCounterData.get("Counter"));
		Boolean specialDays = (Boolean) addCounterData.get("SpecialDays");
		addCounter(key, position, beforeCounter, counter, specialDays);
	}

	protected abstract JSONObject decodeCounter(JSONObject counter);

	private void deleteCounter(String key, String counterCode) {
		JSONObject element = get(getParent(key));
		((JSONArray) element.get("Counters")).removeIf(counter -> { return ((JSONObject) counter).get("CounterCode").equals(counterCode); });
		((JSONArray) element.get("SpecialDays")).removeIf(counter -> { return ((JSONObject) counter).get("CounterCode").equals(counterCode); });
	}

	protected void addCounter(JSONArray counters, JSONArray priorities, Integer position, String beforeCounter, JSONObject value, Boolean specialDaysAdd) {
		//Разделяем полученный массив счетчиков на денежные и неденежные. Вычисляем денежный ли полученный новый счетчик
		JSONArray moneyCounters = new JSONArray();
		JSONArray nonMoneyCounters = new JSONArray();
		counters.forEach(c -> {
			JSONObject counter =(JSONObject) c;
			if (counter.containsKey("CounterMoney") && counter.get("CounterMoney").equals(Boolean.TRUE)) moneyCounters.add(counter);
			else nonMoneyCounters.add(counter);
		});
		Boolean isMoneyNewCounter = value.containsKey("CounterMoney") && value.get("CounterMoney").equals(Boolean.TRUE);

		//Добавляем счетчики в масивы денежных и неденежных счетчиков в зависимости от необходимой позиции
		switch (position){
			case -1:
				if(isMoneyNewCounter) moneyCounters.add(value);
				else nonMoneyCounters.add(value);
				break;
			case 0:
				if(isMoneyNewCounter) moneyCounters.add(0, value);
				else nonMoneyCounters.add(0, value);
				break;
			case 1:
				// Если существует валидный beforeCounter, то вставляем новый счетчик до найденного значения, а если найденного значения нет или типы счетчиков не совпадают,
				// то последним в свой тип счетчиков
				int beforeAddCounter = moneyCounters.size()+nonMoneyCounters.size();
				int afterAddCounter = beforeAddCounter;
				if(beforeCounter.length() > 0){
					if(isMoneyNewCounter){
						for(int i=0; i < moneyCounters.size(); i++){
							JSONObject counter = (JSONObject)moneyCounters.get(i);
							if(counter.get("CounterCode").equals(beforeCounter)) {
								moneyCounters.add(i, value);
								afterAddCounter++;
								break;
							}
						}
					}
					else if(!isMoneyNewCounter){
						for(int i=0; i < nonMoneyCounters.size(); i++){
							JSONObject counter = (JSONObject)nonMoneyCounters.get(i);
							if(counter.get("CounterCode").equals(beforeCounter)) {
								nonMoneyCounters.add(i, value);
								afterAddCounter++;
								break;
							}
						}
					}
				}
				if (beforeAddCounter == afterAddCounter){
					if(isMoneyNewCounter) moneyCounters.add(value);
					else nonMoneyCounters.add(value);
				}
		}

		//Если есть приоритеты, то добавляем счетчики в зависимости от приоритетов, если же нет, то сначала будут идти неденежные, а потом денежные
		if (priorities != null && priorities.size() > 0) {
			int priority = (Integer) priorities.get(0);
			switch(priority){
				case 0:
					counters.clear();
					nonMoneyCounters.forEach(mc -> counters.add(mc));
					moneyCounters.forEach(mc -> counters.add(mc));
					break;
				case 1:
					counters.clear();
					moneyCounters.forEach(mc -> counters.add(mc));
					nonMoneyCounters.forEach(mc -> counters.add(mc));
					break;
			}
		}
		else {
			counters.clear();
			nonMoneyCounters.forEach(mc -> counters.add(mc));
			moneyCounters.forEach(mc -> counters.add(mc));
		}
	}

	protected void addCounter(String key, Integer position, String beforeCounter, JSONObject value, Boolean specialDaysAdd) {
		JSONObject element = get(getParent(key));
		if (element == null) return;
		if (value == null) return;
		String counterCode = (String) value.get("CounterCode");
		deleteCounter(key, counterCode);
		addCounter((JSONArray) element.get("Counters"), (JSONArray) element.get("Priorities"), position, beforeCounter, value, specialDaysAdd);
		if (specialDaysAdd) {
			addSpecialDays(element, "CounterCode", counterCode);
		}
	}

	private void addSpecialPrice(String key, JSONObject priceData) {
		JSONObject price = decodeSpecialPrice((JSONObject) priceData.get("Price"));
		Boolean specialDays = (Boolean) priceData.get("SpecialDays");
		addSpecialPrice(key, price, specialDays);
	}

	protected void addSpecialPrice(String key, JSONObject price, Boolean specialDays) {
		JSONObject specialPrice = get(getParent(key));
		if (specialPrice == null) return;
		JSONArray priceList = (JSONArray) specialPrice.get("Price");
		if (priceList == null) return;
		String serviceCode = (String) price.get("ServiceCode");
		deleteSpecialPrice(key, serviceCode);
		priceList.add(price);
		if (specialDays) {
			addSpecialDays(specialPrice, "ServiceCode", serviceCode);
		}
	}

	private void deleteSpecialPrice(String key, String serviceCode) {
		JSONObject specialPrice = get(getParent(key));
		((JSONArray) specialPrice.get("Price")).removeIf(price -> { return ((JSONObject) price).get("ServiceCode").equals(serviceCode); });
		((JSONArray) specialPrice.get("SpecialDays")).removeIf(price -> { return ((JSONObject) price).get("ServiceCode").equals(serviceCode); });
	}

	protected abstract JSONObject decodeSpecialPrice(JSONObject price);

	private void addSpecialDays(JSONObject element, String name, String code) {
		JSONArray specialDays = (JSONArray) element.get("SpecialDays");
		if (specialDays != null) {
			for (int weekDay = 0; weekDay < 7; weekDay++) {
				JSONObject specialDay = new JSONObject();
				specialDay.put(name, code);
				specialDay.put("WeekDay", Integer.valueOf(weekDay));
				fillSpecialTime(specialDay);
				specialDays.add(specialDay);
			}
		}
	}

	protected abstract void fillSpecialTime(JSONObject specialDay);

	private void addDiscount(String key, JSONObject discountData) {
		JSONObject value = decodeDiscount((JSONObject) discountData.get("Discount"));
		Boolean specialDaysAdd = (Boolean) discountData.get("SpecialDays");
		addDiscount(key, value, specialDaysAdd);
	}

	protected void addDiscount(String key, JSONObject value, Boolean specialDaysAdd) {
		JSONObject element = get(getParent(key));
		if (element == null) return;
		if (value == null) return;
		String serviceCode = (String) value.get("ServiceCode");
		checkDiscount(key, serviceCode);
		deleteDiscount(key, serviceCode);
		JSONArray discounts = (JSONArray) get(getParent(key)).get("Discounts");
		discounts.add(value);
		if (specialDaysAdd) {
			addSpecialDays(element, "ServiceCode", serviceCode);
		}
	}

	private void checkDiscount(String key, String discountCode) {
		String parentKey = getParent(key);
		JSONObject element = get(parentKey);
		if (element.get("Discounts") == null) {
			DefaultObject defaultObjectService = new DefaultObjectService();
			JSONObject discount = defaultObjectService.createDefaultObject("FFWithPriorityRulesAdvanced", parentKey);
			JSONObject parent = get(getParent(parentKey));
			parent.put("Discounts", discount);
		}
	}

	private void deleteDiscount(String key, String discountCode) {
		JSONObject element = get(getParent(key));
		((JSONArray) element.get("Discounts")).removeIf(discount -> { return ((JSONObject) discount).get("ServiceCode").equals(discountCode); });
		((JSONArray) element.get("SpecialDays")).removeIf(discount -> { return ((JSONObject) discount).get("ServiceCode").equals(discountCode); });
	}

	protected abstract JSONObject decodeDiscount(JSONObject discount);

	private Integer timeToInt(String value) {
		String time[] = value.split(":");
		try {
			return Integer.valueOf(time[0]) * 3600 + Integer.valueOf(time[1]) * 60 + Integer.valueOf(time[2]);
		} catch (NumberFormatException e) {
			return Integer.MAX_VALUE;
		}
	}

	protected void removeThreshold(JSONArray thresholds, Object threshold) {
		thresholds.removeIf(t -> ((JSONObject) t).get("Threshold").equals(threshold));
	}

	protected void addThreshold(JSONArray thresholds, JSONObject value) {
		Object threshold = value.get("Threshold");
		if (threshold instanceof Number) {
			Number newThreshold = (Number) threshold;
			for (int pos = 0; pos < thresholds.size(); pos++) {
				if (newThreshold.longValue() < ((Number) ((JSONObject) thresholds.get(pos)).get("Threshold")).longValue()) {
					thresholds.add(pos, value);
					return;
				}
			}
		} else if (threshold instanceof String) {
			Integer newThreshold = timeToInt((String) threshold);
			for (int pos = 0; pos < thresholds.size(); pos++) {
				if (newThreshold < timeToInt((String) ((JSONObject) thresholds.get(pos)).get("Threshold"))) {
					thresholds.add(pos, value);
					return;
				}
			}
		} else if (threshold instanceof JSONObject) {
			Number newThreshold = (Number) ((JSONObject) threshold).get("Value");
			for (int pos = 0; pos < thresholds.size(); pos++) {
				if (newThreshold.longValue() < ((Number) ((JSONObject) ((JSONObject) thresholds.get(pos)).get("Threshold")).get("Value")).longValue()) {
					thresholds.add(pos, value);
					return;
				}
			}
		}
		thresholds.add(value);
	}

	private void addDynamicThreshold(String key, JSONObject thresholdData) {
		JSONObject value = decodeDynamicThreshold(key, thresholdData);
		if (value == null) return;
		JSONArray thresholds = getArray(key);
		if (thresholds == null) {
			thresholds = (JSONArray) initialize(key);
			if (thresholds == null) return;
		}
		if(!isDefaultExist(thresholds)) {
			addThreshold(thresholds, createDefaultThreshold());
		}
		if (key.contains("ExtendedPrice") && thresholds.size() > 0 && thresholds.get(0) instanceof JSONArray) {
			thresholds = (JSONArray) thresholds.get(0);
		}
		removeThreshold(thresholds, value.get("Threshold"));
		addThreshold(thresholds, value);
	}

	protected JSONObject createDefaultThreshold(){
		return new JSONObject();
	}

	protected boolean isDefaultExist(JSONArray thresholds){
		return true;
	}

	protected abstract JSONObject decodeDynamicThreshold(String key, JSONObject threshold);

	protected JSONObject createDependentPrice(Object pricePerUnit, Object unit) {
		JSONObject dependentPrice = new JSONObject();
		dependentPrice.put("PositionFNF", Long.valueOf(0));
		dependentPrice.put("NumberFNF", Long.valueOf(0));
		dependentPrice.put("PricePerUnit", pricePerUnit);
		if (unit != null) dependentPrice.put("Unit", unit);
		return dependentPrice;
	}

	protected JSONObject createDependentPrice(Object pricePerUnit) {
		return createDependentPrice(pricePerUnit, null);
	}

	protected void addDefaultValue(String type){}

	protected JSONObject createSpecialPrice() {
		JSONObject specialPrice = new JSONObject();
		specialPrice.put("SpecialDays", new JSONArray());
		specialPrice.put("CalculationType", Long.valueOf(0));
		specialPrice.put("Price", new JSONArray());
		return specialPrice;
	}

	private void addStepThreshold(String key, JSONObject thresholdData) {
		JSONObject value = decodeStepThreshold(key, thresholdData);
		if (value == null) return;
		JSONArray thresholds = getArray(key);
		if (thresholds == null) {
			thresholds = (JSONArray) initialize(key);
			if (thresholds == null) return;
		}
		if(!isDefaultExist(thresholds)) {
			addThreshold(thresholds, createDefaultThreshold());
		}
		if (key.contains("ExtendedPrice") && thresholds.size() > 0 && thresholds.get(0) instanceof JSONArray) {
			thresholds = (JSONArray) thresholds.get(0);
		}
		removeThreshold(thresholds, value.get("Threshold"));
		addThreshold(thresholds, value);
	}

	protected abstract JSONObject decodeStepThreshold(String key, JSONObject threshold);

}
