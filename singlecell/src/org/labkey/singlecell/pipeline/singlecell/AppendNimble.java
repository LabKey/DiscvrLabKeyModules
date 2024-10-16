package org.labkey.singlecell.pipeline.singlecell;

import org.json.JSONArray;
import org.json.JSONObject;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.sequenceanalysis.pipeline.AbstractPipelineStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.PipelineContext;
import org.labkey.api.sequenceanalysis.pipeline.SequenceOutputHandler;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.api.singlecell.pipeline.SeuratToolParameter;
import org.labkey.api.singlecell.pipeline.SingleCellStep;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppendNimble extends AbstractRDiscvrStep
{
    public AppendNimble(PipelineContext ctx, AppendNimble.Provider provider)
    {
        super(provider, ctx);
    }

    public static class Provider extends AbstractPipelineStepProvider<SingleCellStep>
    {
        public Provider()
        {
            super("AppendNimble", "Append Nimble Data", "Nimble/Rdiscvr", "The seurat object will be subset based on the expression below, which is passed directly to Seurat's subset(subset = X).", List.of(
                    ToolParameterDescriptor.create("nimbleGenomes", "Genomes", "Genomes to include", "singlecell-nimbleappendpanel", new JSONObject()
                    {{
                        put("allowBlank", false);
                    }}, null),
                    SeuratToolParameter.create("retainAmbiguousFeatures", "Retain Ambiguous Features", "If checked, features hitting more than one reference will be retained", "checkbox", new JSONObject()
                    {{
                        put("check", false);
                    }}, false, null, true),
                    SeuratToolParameter.create("ensureSamplesShareAllGenomes", "Ensure Samples Share All Genomes", "If checked, the job will fail unless nimble data is found for each requested genome for all samples", "checkbox", new JSONObject()
                    {{
                        put("check", true);
                    }}, true, null, true),
                    SeuratToolParameter.create("maxLibrarySizeRatio", "Max Library Size Ratio", "This normalization relies on the assumption that the library size of the assay being normalized in negligible relative to the assayForLibrarySize. To verify this holds true, the method will error if librarySize(assayToNormalize)/librarySize(assayForLibrarySize) exceeds this value", "ldk-numberfield", new JSONObject()
                    {{
                        put("decimalPrecision", 4);
                    }}, 0.1, null, true)
            ), Arrays.asList("sequenceanalysis/field/GenomeField.js", "/singlecell/panel/NimbleAppendPanel.js"), null);
        }

        @Override
        public AppendNimble create(PipelineContext ctx)
        {
            return new AppendNimble(ctx, this);
        }
    }

    @Override
    public Collection<String> getRLibraries()
    {
        Set<String> ret = new HashSet<>();
        ret.add("Seurat");
        ret.addAll(super.getRLibraries());

        return ret;
    }

    @Override
    protected Chunk createParamChunk(SequenceOutputHandler.JobContext ctx, List<SeuratObjectWrapper> inputObjects, String outputPrefix) throws PipelineJobException
    {
        Chunk ret = super.createParamChunk(ctx, inputObjects, outputPrefix);

        ret.bodyLines.add("nimbleGenomes <- list(");
        String genomeStr = getProvider().getParameterByName("nimbleGenomes").extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), String.class);
        JSONArray json = new JSONArray(genomeStr);
        String delim = "";
        for (int i = 0; i < json.length(); i++)
        {
            JSONArray arr = json.getJSONArray(i);
            if (arr.length() != 2)
            {
                throw new PipelineJobException("Unexpected value: " + json.getString(i));
            }

            int genomeId = arr.getInt(0);
            String targetAssay = arr.getString(1);
            ret.bodyLines.add("\t" + delim + "'" + genomeId + "' = '" + targetAssay + "'");
            delim = ",";
        }
        ret.bodyLines.add(")");

        return ret;
    }

    @Override
    public String getFileSuffix()
    {
        return "nimble";
    }
}
