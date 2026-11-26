import { computed, inject, ref, type InjectionKey, type Ref } from 'vue'
import { number } from './model'

export type WeightUnit = 'JIN' | 'GRAM'
export interface Preferences { weightUnit: WeightUnit }
export const weightUnitKey: InjectionKey<Readonly<Ref<WeightUnit>>> = Symbol('weight-unit')

export function useWeight() {
  const preference = inject(weightUnitKey, ref<WeightUnit>('JIN'))
  const unitLabel = computed(() => preference.value === 'GRAM' ? '克' : '斤')
  const weightStep = computed(() => preference.value === 'GRAM' ? 0.5 : 0.001)
  const displayWeight = (value: number, unit: string | null | undefined = '斤') =>
    preference.value === 'GRAM' && unit === '斤' ? Number((value * 500).toFixed(6)) : value
  const baseWeight = (value: number) => preference.value === 'GRAM' ? value / 500 : value
  const displayUnit = (unit: string | null | undefined = '斤') => unit === '斤' ? unitLabel.value : (unit || '')
  const formatWeight = (value: number, unit: string | null | undefined = '斤') => number(displayWeight(value, unit))
  return { preference, unitLabel, weightStep, displayWeight, baseWeight, displayUnit, formatWeight }
}
