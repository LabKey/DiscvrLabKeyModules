package org.labkey.singlecell.pipeline.singlecell;

import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.model.Readset;
import org.labkey.api.sequenceanalysis.pipeline.AbstractPipelineStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.PipelineContext;
import org.labkey.api.sequenceanalysis.pipeline.SequenceOutputHandler;
import org.labkey.api.singlecell.CellHashingService;
import org.labkey.api.singlecell.pipeline.SingleCellOutput;
import org.labkey.api.singlecell.pipeline.SingleCellStep;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.singlecell.CellHashingServiceImpl;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RunCellHashing extends AbstractCellHashingCiteseqStep
{
    public static final String CATEGORY = "Seurat Cell Hashing Calls";

    public RunCellHashing(PipelineContext ctx, RunCellHashing.Provider provider)
    {
        super(provider, ctx);
    }

    public static class Provider extends AbstractPipelineStepProvider<SingleCellStep>
    {
        public Provider()
        {
            super("RunCellHashing", "Possibly Run/Store Cell Hashing", "cellhashR", "If available, this will run cellhashR to score cells by sample.", CellHashingService.get().getHashingCallingParams(), null, null);
        }

        @Override
        public RunCellHashing create(PipelineContext ctx)
        {
            return new RunCellHashing(ctx, this);
        }
    }

    @Override
    public Collection<String> getRLibraries()
    {
        return PageFlowUtil.set("cellhashR");
    }

    @Override
    public String getDockerContainerName()
    {
        return "ghcr.io/bimberlab/cellhashr:latest";
    }

    @Override
    protected Map<Integer, File> prepareCountData(SingleCellOutput output, SequenceOutputHandler.JobContext ctx, List<SeuratObjectWrapper> inputObjects, String outputPrefix) throws PipelineJobException
    {
        Map<Integer, File> dataIdToCalls = new HashMap<>();

        for (SeuratObjectWrapper wrapper : inputObjects)
        {
            File hashingCalls = null;
            if (wrapper.getSequenceOutputFileId() == null)
            {
                throw new PipelineJobException("Computing and appending Hashing or CITE-seq is only supported using seurat objects will a single input dataset. Consider moving this step easier in your pipeline, before merging or subsetting");
            }

            File allCellBarcodes = CellHashingServiceImpl.get().getCellBarcodesFromSeurat(wrapper.getFile());

            //NOTE: by leaving null, it will simply drop the barcode prefix. Upstream checks should ensure this is a single-readset object
            File cellBarcodesParsed = CellHashingServiceImpl.get().subsetBarcodes(allCellBarcodes, null);
            ctx.getFileManager().addIntermediateFile(cellBarcodesParsed);

            Readset parentReadset = ctx.getSequenceSupport().getCachedReadset(wrapper.getSequenceOutputFile().getReadset());
            if (parentReadset == null)
            {
                throw new PipelineJobException("Unable to find readset for outputfile: " + wrapper.getSequenceOutputFileId());
            }

            Set<String> htosPerReadset = CellHashingServiceImpl.get().getHtosForParentReadset(parentReadset.getReadsetId(), ctx.getSourceDirectory(), ctx.getSequenceSupport(), false);
            if (htosPerReadset.size() > 1)
            {
                ctx.getLogger().info("Total barcodes for readset: " + htosPerReadset.size());

                CellHashingService.CellHashingParameters params = CellHashingService.CellHashingParameters.createFromStep(ctx, this, CellHashingService.BARCODE_TYPE.hashing, null, parentReadset, null);
                params.outputCategory = CATEGORY;
                params.genomeId = wrapper.getSequenceOutputFile().getLibrary_id();
                params.cellBarcodeWhitelistFile = cellBarcodesParsed;
                File existingCountMatrixUmiDir = CellHashingService.get().getExistingFeatureBarcodeCountDir(parentReadset, CellHashingService.BARCODE_TYPE.hashing, ctx.getSequenceSupport());
                params.allowableHtoOrCiteseqBarcodes = htosPerReadset;

                hashingCalls = CellHashingService.get().generateHashingCallsForRawMatrix(parentReadset, output, ctx, params, existingCountMatrixUmiDir);
            }
            else if (htosPerReadset.size() == 1)
            {
                ctx.getLogger().info("Only single HTO used for lane, skipping cell hashing calling");
            }
            else
            {
                ctx.getLogger().info("No HTOs found for readset");
            }

            dataIdToCalls.put(wrapper.getSequenceOutputFileId(), hashingCalls);
        }

        return dataIdToCalls;
    }

    @Override
    public boolean isIncluded(SequenceOutputHandler.JobContext ctx, List<SequenceOutputFile> inputs) throws PipelineJobException
    {
        return CellHashingService.get().usesCellHashing(ctx.getSequenceSupport(), ctx.getSourceDirectory());
    }

    @Override
    public String getFileSuffix()
    {
        return "hashing";
    }
}
