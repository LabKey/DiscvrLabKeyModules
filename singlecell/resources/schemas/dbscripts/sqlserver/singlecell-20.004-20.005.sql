CREATE TABLE singlecell.singlecellDatasets (
    rowid int IDENTITY(1,1),
    name varchar(1000),
    loupeFileId int,
    readsetId int,
    cDNAId int,
    status nvarchar(100),

    container entityid,
    created datetime,
    createdby int,
    modified datetime,
    modifiedby int,

    constraint PK_singlecellDatasets PRIMARY KEY (rowid)
);

GO

INSERT INTO singlecell.singlecellDatasets
(loupeFileId, name, readsetId, cDNAId, status, container, created, createdby, modified, modifiedby)
SELECT
    rowId as loupeFileId,
    (SELECT name FROM sequenceanalysis.sequence_readsets WHERE sequence_readsets.rowid = readset) as name,
    readset as readsetId,
    (SELECT max(c.rowid) as cDNAId FROM singlecell.cdna_libraries c WHERE c.readsetId = outputfiles.readset) as cDNAId,
    null as status,

    container, created, createdby, modified, modifiedby
FROM sequenceanalysis.outputfiles WHERE category = '10x Loupe File';