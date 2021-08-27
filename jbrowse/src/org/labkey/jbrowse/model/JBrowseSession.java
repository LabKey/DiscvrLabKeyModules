package org.labkey.jbrowse.model;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.labkey.api.data.CompareType;
import org.labkey.api.data.Container;
import org.labkey.api.data.ContainerManager;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.Table;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.exp.api.ExpData;
import org.labkey.api.exp.api.ExperimentService;
import org.labkey.api.files.FileContentService;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.query.FieldKey;
import org.labkey.api.security.User;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceGenome;
import org.labkey.api.util.FileUtil;
import org.labkey.api.util.JobRunner;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.jbrowse.JBrowseManager;
import org.labkey.jbrowse.JBrowseSchema;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * User: bimber
 * Date: 7/14/2014
 * Time: 2:54 PM
 */
public class JBrowseSession
{
    private static final Logger _log = LogManager.getLogger(JBrowseSession.class);

    private int _rowId;
    private String _name;
    private String _description;
    private String _jobid;
    private Integer _libraryId;
    private String _jsonConfig;
    private String _objectId;
    private String _container;
    private List<DatabaseMember> _members = null;

    public JBrowseSession()
    {

    }

    public int getRowId()
    {
        return _rowId;
    }

    public void setRowId(int rowId)
    {
        _rowId = rowId;
    }

    public String getName()
    {
        return _name;
    }

    public void setName(String name)
    {
        _name = name;
    }

    public String getDescription()
    {
        return _description;
    }

    public void setDescription(String description)
    {
        _description = description;
    }

    public String getContainer()
    {
        return _container;
    }

    public void setContainer(String container)
    {
        _container = container;
    }

    public Container getContainerObj()
    {
        return _container == null ? null : ContainerManager.getForId(_container);
    }

    public String getObjectId()
    {
        return _objectId;
    }

    public void setObjectId(String objectId)
    {
        _objectId = objectId;
    }

    public String getJobid()
    {
        return _jobid;
    }

    public void setJobid(String jobid)
    {
        _jobid = jobid;
    }

    public Integer getLibraryId()
    {
        return _libraryId;
    }

    public void setLibraryId(Integer libraryId)
    {
        _libraryId = libraryId;
    }

    public String getJsonConfig()
    {
        return _jsonConfig;
    }

    public void setJsonConfig(String jsonConfig)
    {
        _jsonConfig = jsonConfig;
    }

    public List<DatabaseMember> getMembers()
    {
        if (_members == null)
        {
            TableInfo ti = JBrowseSchema.getInstance().getTable(JBrowseSchema.TABLE_DATABASE_MEMBERS);
            _members = new TableSelector(ti, new SimpleFilter(FieldKey.fromString("database"), _objectId), null).getArrayList(DatabaseMember.class);
        }

        return _members;
    }

    public static void onDatabaseDelete(String containerId, final String databaseId, boolean asyncDelete) throws IOException
    {
        Container c = ContainerManager.getForId(containerId);
        if (c == null)
        {
            return;
        }

        //delete children
        TableInfo ti = JBrowseSchema.getInstance().getTable(JBrowseSchema.TABLE_DATABASE_MEMBERS);
        int deleted = Table.delete(ti, new SimpleFilter(FieldKey.fromString("database"), databaseId, CompareType.EQUAL));

        //then delete files
        FileContentService fileService = FileContentService.get();
        File fileRoot = fileService == null ? null : fileService.getFileRoot(c, FileContentService.ContentType.files);
        if (fileRoot == null || !fileRoot.exists())
        {
            return;
        }

        File jbrowseDir = new File(fileRoot, ".jbrowse");
        if (!jbrowseDir.exists())
        {
            return;
        }

        File databaseDir = new File(jbrowseDir, "databases");
        if (!databaseDir.exists())
        {
            return;
        }

        final File databaseDir2 = new File(databaseDir, databaseId);
        if (databaseDir2.exists())
        {
            _log.info("deleting jbrowse database dir: " + databaseDir2.getPath());
            if (!asyncDelete)
            {
                FileUtils.deleteDirectory(databaseDir2);
            }
            else
            {
                JobRunner.getDefault().execute(new Runnable(){
                    public void run()
                    {
                        try
                        {
                            _log.info("deleting jbrowse database dir async: " + databaseDir2.getPath());
                            FileUtils.deleteDirectory(databaseDir2);
                        }
                        catch (IOException e)
                        {
                            _log.error("Error background deleting JBrowse database dir for: " + databaseId, e);
                        }
                    }
                });
            }
        }
    }

    public static JBrowseSession getForId(String objectId)
    {
        return new TableSelector(JBrowseSchema.getInstance().getSchema().getTable(JBrowseSchema.TABLE_DATABASES)).getObject(objectId, JBrowseSession.class);
    }

    public JSONObject getConfigJson(User u, Logger log) throws PipelineJobException
    {
        JSONObject ret = new JSONObject();

        ReferenceGenome rg = SequenceAnalysisService.get().getReferenceGenome(_libraryId, u);
        if (rg == null)
        {
            throw new IllegalArgumentException("Unable to find genome: " + _libraryId);
        }

        ret.put("assemblies", new JSONArray(){{
            put(getAssemblyJson(rg));
        }});

        ret.put("configuration", new JSONObject());
        ret.put("connections", new JSONArray());

        // Start with all tracks from this genome:
        List<JsonFile> toAdd = new ArrayList<>(getGenomeTracks());
        toAdd.forEach(x -> x.setCategory("Reference Annotations"));

        // Then resources specific to this session:
        for (DatabaseMember m : getMembers())
        {
            JsonFile jsonFile = m.getJson();
            if (jsonFile == null)
            {
                continue;
            }

            toAdd.add(jsonFile);
        }

        JSONArray tracks = new JSONArray();
        for (JsonFile jsonFile : toAdd)
        {
            JSONObject o = jsonFile.toTrackJson(u, rg, log);
            if (o != null)
            {
                tracks.put(o);
            }
        }

        ret.put("tracks", tracks);
        //ret.put("defaultSession", getDefaultSessionJson(u, tracks));

        return ret;
    }

    private List<JsonFile> getGenomeTracks()
    {
        // Find active tracks:
        SimpleFilter filter = new SimpleFilter(FieldKey.fromString("library_id"), getLibraryId(), CompareType.IN);
        filter.addCondition(FieldKey.fromString("datedisabled"), null, CompareType.ISBLANK);
        List<Integer> trackIds = new TableSelector(JBrowseManager.get().getSequenceAnalysisTable("reference_library_tracks"), PageFlowUtil.set("rowid"), filter, null).getArrayList(Integer.class);

        if (trackIds.isEmpty())
        {
            return Collections.emptyList();
        }

        return new TableSelector(JBrowseSchema.getInstance().getTable(JBrowseSchema.TABLE_JSONFILES), new SimpleFilter(FieldKey.fromString("trackid"), trackIds, CompareType.IN), null).getArrayList(JsonFile.class);
    }

    public JSONObject getDefaultSessionJson(User u, JSONArray tracks)
    {
        JSONObject ret = new JSONObject();
        ret.put("name", getName());

        JSONObject viewJson = new JSONObject();
        viewJson.put("id", _objectId);
        viewJson.put("type", "LinearGenomeView");

        JSONArray defaultTracks = new JSONArray();
        for (JSONObject o : tracks.toJSONObjectArray())
        {
            if (o.optBoolean("shownByDefault", false)){
                defaultTracks.put(new JSONObject(){{
                    put("type", o.get("type"));
                    put("configuration", o.get("trackId"));

                    //TODO: displays
                }});
            }
        }
        viewJson.put("tracks", defaultTracks);

        ret.put("view", viewJson);

        return ret;
    }

    public static JSONObject getAssemblyJson(ReferenceGenome rg)
    {
        JSONObject ret = new JSONObject();

        ret.put("name", rg.getName());
        ret.put("sequence", new JSONObject(){{
            put("type", "ReferenceSequenceTrack");
            put("trackId", getAssemblyName(rg));
            put("adapter", getIndexedFastaAdapter(rg));
        }});

        return ret;
    }

    public static String getAssemblyName(ReferenceGenome rg)
    {
        return FileUtil.makeLegalName(rg.getName()) + "-ReferenceSequenceTrack";
    }

    public static JSONObject getIndexedFastaAdapter(ReferenceGenome rg)
    {
        ExpData d = ExperimentService.get().getExpData(rg.getFastaExpDataId());
        String url = d.getWebDavURL(ExpData.PathType.full);

        JSONObject ret = new JSONObject();
        ret.put("type", "IndexedFastaAdapter");
        ret.put("fastaLocation", new JSONObject(){{
            put("uri", url);
        }});
        ret.put("faiLocation", new JSONObject(){{
            put("uri", url + ".fai");
        }});

        return ret;
    }
}
