import { passesInfoFilters, passesSampleFilters } from '../utils';
import { deserializeFilters } from '../Browser/plugins/ExtendedVariantPlugin/InfoFilterWidget/filterUtil';
import ExtendedVcfFeature from '../Browser/plugins/ExtendedVariantPlugin/ExtendedVariantAdapter/ExtendedVcfFeature';

const prepareInfoField = (rawFeature: ExtendedVcfFeature, propKey: string) => {
    //const info = rawFeature.getInfoFieldMeta(propKey)
    const rawVal = rawFeature.get("INFO")[propKey]
    if (Array.isArray(rawVal)) {
        return(rawVal.filter(x => x !== null && x !== '').join(", ") || "")
    }
    else {
        return(rawVal)
    }
}

export function APIDataToRows(data: any, trackId: string): any[] {
  let ret = data.map((obj: any, idx: any) => {
    if ("ANN" in obj) {
      obj["variant_type"] = parseAnnField(obj.ANN, 1, 'custom')
      obj["overlapping_genes"] = parseAnnField(obj.ANN, 3, null)
    }

    obj["id"] = idx;
    obj["trackId"] = trackId;
    return obj;
  })

  return ret
}

// Takes a list of ANN annotations and retrieves all unique genes from it.
function parseAnnField(anns, fieldIdx, ignoredTerms) {
  if (!anns) {
    return ''
  }

  let geneSet = new Set()
  for (let ann of anns) {
    let geneName = ann.split("|")[fieldIdx]
    if (ignoredTerms && ignoredTerms.includes(geneName)) {
      continue
    }

    if (geneName) {
      geneSet.add(geneName)
    }
  }

  return Array.from(geneSet).join(", ")
}

// Filters features according to the data from the relevant widgets
export function filterFeatures(features, activeSamples, filters) {
  let ret = []

  let processedActiveSamples = activeSamples === "" ? [] : activeSamples.split(",")
  let processedFilters = deserializeFilters(JSON.parse(filters))

  features.forEach((feature) => {
    if (passesSampleFilters(feature, processedActiveSamples) && passesInfoFilters(feature, processedFilters)) {
      ret.push(feature)
    }
  })

  return ret
}