package org.labkey.singlecell.pipeline.singlecell;

import org.json.JSONObject;
import org.labkey.api.sequenceanalysis.pipeline.AbstractPipelineStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.PipelineContext;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.api.singlecell.pipeline.SingleCellStep;

import java.util.Arrays;

public class MergeSeurat extends AbstractCellMembraneStep
{
    public MergeSeurat(PipelineContext ctx, MergeSeurat.Provider provider)
    {
        super(provider, ctx);
    }

    public static class Provider extends AbstractPipelineStepProvider<SingleCellStep>
    {
        public Provider()
        {
            super("MergeSeurat", "Merge Seurat Objects", "OOSAP", "This will merge the incoming seurat objects into a single object, merging all assays. Note: this will discard any normalization or DimRedux data, and performs zero validation to ensure this is compatible with downstream steps.", Arrays.asList(
                    ToolParameterDescriptor.create("projectName", "New Dataset Name", "The updated baseline for this merged object.", "textfield", new JSONObject(){{
                        put("allowBlank", false);
                    }}, null)
            ), null, null);
        }


        @Override
        public MergeSeurat create(PipelineContext ctx)
        {
            return new MergeSeurat(ctx, this);
        }
    }
}
