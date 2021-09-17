## Digipat Annotation Transfer Tools - README

Tested on QuPath 0.2.3.

A) Workflow NDPA > QuPath > Cytomine

 1. Add batch of NDPI images to QuPath project:
    - In QuPath, load and run script: AddNDPIFiles2QuPathProject.groovy
    - Adds images to new or existing project.Images are found recursively from project directory. 
    
 2. In QuPath, open project from step 1.

 3. Import NDPA annotations to QuPath project:
	  - In QuPath, open script: QuPath-NDPA-annotations.groovy
	  - 3.1. Summary of NDPA annotations:
		 - Set script variable: summary1OrImport2OrExport3 = 1
	      - Run script.
	      - Prints a summary of annotations, including numbers, labels (important for eventual mapping/recoding when importing to QuPath), types and problems.
	      - POINTER and LINEARMEASURE type annotations are currently not supported.
          - POINTER annotations with a label starting with % (e.g. 30%) are currently skipped and not counted (just a project specific tweak O:) ).
      - 3.2. Import of NDPA annotations into QuPath:
	      - Set variable: summary1OrImport2OrExport3 = 2.
	      - Set variable: useLabelMapping:
	          - false: Original NDPA labels are used for setting the class and name of QuPath annotations.
	          - true: Original NDPA labels (as listed in summary 3.1) are recoded for setting the class and name of QuPath annotations (e.g. T -> Tumor).
	              - Set map of labels for recoding e.g.:
		            - def labelMapping = [  
                t: "Tumor-Duodenum",  
                M: "Mucin",  
                T: "Tumor-Pancreas",  
                s: "Serum",  
                L: "Lymphocytes",  
                B: "Blood",  
                pp: "Macrophages"  
            ]  
                  - Mapping must be defined for **every** NDAP label. Failure of mapping will result in exception and script termination, to ensure data integrity.
          - As convention, as QuPath does not support multiple classes/terms/labels to be associated with the same annotation, when this is needed, the several classes are separated by '-' (e.g. "Tumor-duodenum" indicates an approximation to double labeling). These can be later split into multiple labels for the same annotation when importing to Cytomine.
          - Run script.
          - Any previous annotation in the images will be removed before importing the new ones.
          - Annotation colors are preserved/updated as in NDPA.

  4. Export QuPath annotations to Cytomine JSON (CytomJSON) format
	  - In QuPath, open script: QuPath-CytomJSON-annotations.groovy
	  - Set variable: importToQuPath1OrExportFromQuPath2 = 2
	  - Set variables: jsonDir (ended with /) and projectName. Recommended to set jsonDir to out/ folder (see recommended folder structure below).
	  - Run script.
	  - QuPath annotation labels are splitted by '-' (e.g. "Tumor-Duodenum"), generating when present multiple labels for the same annotation (e.g. "Tumor" and "Duodenum"). Multiple labeling for individual annotations is supported by Cytomine, but not by QuPath.
	  - Output JSON file is written to jsonDir.

  5. Import CytomJSON annotations to Cytomine server:
      - In IDE (e.g. Eclipse) or text editor, open script: Cytomine-CytomJSON-annotations.groovy
      - Configure in properties file 'config/CytomineConn.properties' externalized Cytomine connection credentials (host, publicKey, privateKey) and project name (projectName) (e.g. projectName=My-Digipat-Project).
      - Add to classpath: cytomine-java-client.jar (https://doc.cytomine.org/dev-guide/clients/java/installation).
      - Script will download through grape dependency org.codehaus.groovy:groovy-json.
      - Set variable: import1OrExport2 = 1
      - Run script.
      - Annotation terms are according to pre-existing ontology in Cytomine.
      - In case an annotation term lacks in the ontology it will be created, preserving QuPath class color. However, color scheme is not meaningful when multiple labeling.


B) Workflow Cytomine > QuPath > NDPA
  1. If needed, add batch of NDPI images to QuPath project (as in A1).

  2. In QuPath, open project.	

  3. Export Cytomine annotations in CytomJSON format:
      - In IDE (e.g. Eclipse) or text editor, open script: Cytomine-CytomJSON-annotations.groovy
      - Configure in properties file 'config/CytomineConn.properties' externalized Cytomine connection credentials (host, publicKey, privateKey) and project name (projectName) (e.g. projectName=My-Digipat-Project).
      - Set variable: import1OrExport2 = 2
      - Optionally, set targetImages (e.g. ["Img-1.ndpi", "Img-2.ndpi"]) whose annotations to be exported. If left empty ([]), annotations from all images in the project will be exported.
      - Run script.
      - CytomJSON annotations are written to out/ folder (see recommended folder structure below)		

  4. Import CytomJSON annotations to QuPath:
      - In QuPath, open script: QuPath-CytomJSON-annotations.groovy
      - Set variable: importToQuPath1OrExportFromQuPath2 = 1
      - Set variables: jsonDir (ended with /) and projectName. Recommended to set jsonDir to out/ folder (see recommended folder structure below).
      - Run script.
      - Previous annotations in the images are removed before importing the new ones.
      - Annotation colors are preserved, with limitation in case of multiple labeling in Cytomine, where only one color will be preserved.

  5. Export QuPath annotations as NDPA:
      - In QuPath, open script: QuPath-NDPA-annotations.groovy
      - Set variable: summary1OrImport2OrExport3 = 3
      - Run script.

C) Recommended folder structure:
  
  DigipatAnnotTransferTools  
  &nbsp;&nbsp;&nbsp;src/  
  &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;AddNDPIFiles2QuPathProject.groovy  
  &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;QuPath-NDPA-annotations.groovy  
  &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;QuPath-CytomJSON-annotations.groovy  
  &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Cytomine-CytomJSON-annotations.groovy  
  &nbsp;&nbsp;&nbsp;config/  
  &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;CytomineCoon.properties  
  &nbsp;&nbsp;&nbsp;jars/  
  &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;cytomine-java-client.jar  
  &nbsp;&nbsp;&nbsp;out/
