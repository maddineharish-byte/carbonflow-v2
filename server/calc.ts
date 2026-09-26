/**
 * CarbonFlow — Deterministic GHG Calculation Engine
 * High-precision decimal arithmetic matching Java BigDecimal standards.
 */
import Decimal from 'decimal.js';
import crypto from 'crypto';
import {
  ActivityData,
  EmissionFactorVersion,
  GwpSet,
  GwpValue,
  Calculation,
  CalculationGasResult,
  EmissionRecord,
} from './types.ts';

// Configure Decimal.js for deterministic arithmetic
Decimal.set({ precision: 28, rounding: Decimal.ROUND_HALF_UP });

export interface UnitNormalizationResult {
  normalizedQuantity: Decimal;
  normalizedUnit: string;
  conversionFactor: Decimal;
}

/**
 * Normalizes input quantity to factor reference unit.
 */
export function normalizeUnits(quantity: number, inputUnit: string, targetUnit: string): UnitNormalizationResult {
  const qty = new Decimal(quantity);
  const from = inputUnit.toUpperCase().trim();
  const to = targetUnit.toUpperCase().trim();

  if (from === to) {
    return { normalizedQuantity: qty, normalizedUnit: to, conversionFactor: new Decimal(1) };
  }

  // Energy conversions
  if (from === 'MWH' && to === 'KWH') {
    const factor = new Decimal(1000);
    return { normalizedQuantity: qty.times(factor), normalizedUnit: 'kWh', conversionFactor: factor };
  }
  if (from === 'KWH' && to === 'MWH') {
    const factor = new Decimal('0.001');
    return { normalizedQuantity: qty.times(factor), normalizedUnit: 'MWh', conversionFactor: factor };
  }
  if (from === 'THERMS' && to === 'KWH') {
    const factor = new Decimal('29.3001');
    return { normalizedQuantity: qty.times(factor), normalizedUnit: 'kWh', conversionFactor: factor };
  }
  if (from === 'M3' && to === 'KWH') {
    // Standard natural gas calorific gross value: 1 m3 ~ 10.55 kWh
    const factor = new Decimal('10.55');
    return { normalizedQuantity: qty.times(factor), normalizedUnit: 'kWh', conversionFactor: factor };
  }

  // Volume conversions
  if ((from === 'GALLONS' || from === 'GALLON' || from === 'GAL') && (to === 'LITRES' || to === 'LITRE' || to === 'L')) {
    const factor = new Decimal('3.785411784');
    return { normalizedQuantity: qty.times(factor), normalizedUnit: 'Litres', conversionFactor: factor };
  }
  if ((from === 'LITRES' || from === 'LITRE' || from === 'L') && (from !== to && to === 'GALLONS')) {
    const factor = new Decimal('1').dividedBy(new Decimal('3.785411784'));
    return { normalizedQuantity: qty.times(factor), normalizedUnit: 'Gallons', conversionFactor: factor };
  }

  // Mass conversions
  if ((from === 'KG' || from === 'KILOGRAMS') && (to === 'METRIC TONNES' || to === 'TONNES' || to === 'T')) {
    const factor = new Decimal('0.001');
    return { normalizedQuantity: qty.times(factor), normalizedUnit: 'Metric Tonnes', conversionFactor: factor };
  }
  if ((from === 'TONNES' || from === 'METRIC TONNES' || from === 'T') && (to === 'KG' || to === 'KILOGRAMS')) {
    const factor = new Decimal(1000);
    return { normalizedQuantity: qty.times(factor), normalizedUnit: 'KG', conversionFactor: factor };
  }

  // Default fallback if identical unit or unhandled ratio
  return { normalizedQuantity: qty, normalizedUnit: targetUnit, conversionFactor: new Decimal(1) };
}

export interface CalculationInput {
  activityData: ActivityData;
  factorVersion: EmissionFactorVersion;
  factorInputUnit: string;
  gwpSet: GwpSet;
  gwpValues: GwpValue[];
  userId?: string;
}

export interface CalculationOutput {
  calculation: Calculation;
  emissionRecord: EmissionRecord;
}

/**
 * Executes deterministic carbon accounting calculation.
 */
export function executeCalculation(input: CalculationInput): CalculationOutput {
  const { activityData, factorVersion, factorInputUnit, gwpSet, gwpValues, userId } = input;

  // 1. Normalize units
  const norm = normalizeUnits(activityData.quantity, activityData.unit, factorInputUnit);
  const normalizedQty = norm.normalizedQuantity;

  // 2. Gas breakdowns
  const gwpMap = new Map<string, Decimal>();
  gwpValues.forEach((gv) => {
    gwpMap.set(gv.gas.toUpperCase(), new Decimal(gv.gwp100yr));
  });

  const gwpCO2 = gwpMap.get('CO2') || new Decimal(1);
  const gwpCH4 = gwpMap.get('CH4') || new Decimal(28);
  const gwpN2O = gwpMap.get('N2O') || new Decimal(273);

  const gasResults: CalculationGasResult[] = [];
  let totalCo2eKgDec = new Decimal(0);

  // If specific individual gas factors are supplied:
  if (factorVersion.co2Factor > 0 || factorVersion.ch4Factor > 0 || factorVersion.n2oFactor > 0) {
    if (factorVersion.co2Factor > 0) {
      const rawCO2Kg = normalizedQty.times(new Decimal(factorVersion.co2Factor));
      const co2eKg = rawCO2Kg.times(gwpCO2);
      totalCo2eKgDec = totalCo2eKgDec.plus(co2eKg);
      gasResults.push({
        gas: 'CO2',
        rawGasEmissionKg: rawCO2Kg.toDecimalPlaces(8).toNumber(),
        gwpApplied: gwpCO2.toNumber(),
        co2eKg: co2eKg.toDecimalPlaces(4).toNumber(),
      });
    }

    if (factorVersion.ch4Factor > 0) {
      const rawCH4Kg = normalizedQty.times(new Decimal(factorVersion.ch4Factor));
      const co2eKg = rawCH4Kg.times(gwpCH4);
      totalCo2eKgDec = totalCo2eKgDec.plus(co2eKg);
      gasResults.push({
        gas: 'CH4',
        rawGasEmissionKg: rawCH4Kg.toDecimalPlaces(8).toNumber(),
        gwpApplied: gwpCH4.toNumber(),
        co2eKg: co2eKg.toDecimalPlaces(4).toNumber(),
      });
    }

    if (factorVersion.n2oFactor > 0) {
      const rawN2OKg = normalizedQty.times(new Decimal(factorVersion.n2oFactor));
      const co2eKg = rawN2OKg.times(gwpN2O);
      totalCo2eKgDec = totalCo2eKgDec.plus(co2eKg);
      gasResults.push({
        gas: 'N2O',
        rawGasEmissionKg: rawN2OKg.toDecimalPlaces(8).toNumber(),
        gwpApplied: gwpN2O.toNumber(),
        co2eKg: co2eKg.toDecimalPlaces(4).toNumber(),
      });
    }
  } else {
    // Composite CO2e factor (e.g. refrigerants, green tariffs)
    const factorCo2e = new Decimal(factorVersion.co2eFactor);
    totalCo2eKgDec = normalizedQty.times(factorCo2e);
    gasResults.push({
      gas: 'CO2e_COMPOSITE',
      rawGasEmissionKg: totalCo2eKgDec.toDecimalPlaces(8).toNumber(),
      gwpApplied: 1,
      co2eKg: totalCo2eKgDec.toDecimalPlaces(4).toNumber(),
    });
  }

  const totalCo2eTonnesDec = totalCo2eKgDec.dividedBy(new Decimal(1000));
  const calcId = crypto.randomUUID();

  // Create immutable snapshot hash
  const hashPayload = `${activityData.id}|${factorVersion.id}|${gwpSet.id}|${normalizedQty.toString()}|${totalCo2eTonnesDec.toString()}`;
  const calculationHash = crypto.createHash('sha256').update(hashPayload).digest('hex');

  const calculation: Calculation = {
    id: calcId,
    organizationId: activityData.organizationId,
    activityDataId: activityData.id,
    reportingPeriodId: activityData.reportingPeriodId,
    factorVersionId: factorVersion.id,
    gwpSetId: gwpSet.id,
    originalQuantity: activityData.quantity,
    originalUnit: activityData.unit,
    normalizedQuantity: normalizedQty.toDecimalPlaces(8).toNumber(),
    normalizedUnit: norm.normalizedUnit,
    conversionFactor: norm.conversionFactor.toDecimalPlaces(10).toNumber(),
    factorValue: factorVersion.co2eFactor,
    factorUnit: factorVersion.factorUnit,
    factorSource: `${factorVersion.source} (${factorVersion.sourceYear})`,
    factorVersion: factorVersion.versionNumber,
    gwpName: `${gwpSet.name}`,
    totalCo2eKg: totalCo2eKgDec.toDecimalPlaces(4).toNumber(),
    totalCo2eTonnes: totalCo2eTonnesDec.toDecimalPlaces(6).toNumber(),
    calculationHash,
    gasResults,
    calculatedAt: new Date().toISOString(),
    calculatedBy: userId,
  };

  // Determine Scope 2 categorization
  let scope2Type: 'LOCATION_BASED' | 'MARKET_BASED' | undefined = undefined;
  if (activityData.scope === 'SCOPE_2') {
    if (activityData.category.toUpperCase().includes('LOCATION') || activityData.activityType.toUpperCase().includes('GRID')) {
      scope2Type = 'LOCATION_BASED';
    } else {
      scope2Type = 'MARKET_BASED';
    }
  }

  const emissionRecord: EmissionRecord = {
    id: crypto.randomUUID(),
    organizationId: activityData.organizationId,
    reportingPeriodId: activityData.reportingPeriodId,
    facilityId: activityData.facilityId,
    calculationId: calcId,
    scope: activityData.scope,
    category: activityData.category,
    scope2Type,
    co2eTonnes: totalCo2eTonnesDec.toDecimalPlaces(6).toNumber(),
    status: 'ACTIVE',
    createdAt: new Date().toISOString(),
  };

  return { calculation, emissionRecord };
}
