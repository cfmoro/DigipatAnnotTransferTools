// Script by Carlos Fernandez Moro
// Inspired by Egor Zindy's script https://forum.image.sc/t/exporting-ndpi-ndpa-annotation-files-from-qupath-code-attached/55418
import qupath.lib.io.GsonTools
import qupath.lib.geom.Point2
import qupath.lib.roi.PolygonROI
import qupath.lib.roi.PolylineROI
import qupath.lib.roi.PointsROI
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.classes.PathClass
import java.awt.Color
import org.locationtech.jts.geom.CoordinateSequence
import org.locationtech.jts.geom.CoordinateSequenceFilter
import org.locationtech.jts.geom.util.PolygonExtracter
import org.locationtech.jts.simplify.TopologyPreservingSimplifier
import org.locationtech.jts.io.WKTWriter

// Configurable variables for script functionality
// Set desired script function (mutually exclusive)
def importToQuPath1OrExportFromQuPath2 = 1
def jsonDir = "/home/bibu/eclipse-workspace/Ndpa2Cytomine/out/" // ended with /
def projectName = "KI-gastrointestinal"

/**
 * Parses Cytomine's WKT points to QuPath coordinates
 */
def parseWKTPoints(pointsStr, imageHeight) {
   // WKT points (x and y separated by space)
    def pointsWKT = pointsStr.split(",")
    def pointsList = []
    // Interate over WKT points
    for(aPointWKT in pointsWKT) {
        //println "Read point: " + aPointWKT
        // Split WKT point by space separator to obtain x and y coordinates
        def coords = aPointWKT.trim().split(" ")
        //println "coords[0]: " + coords[0] +" coords[1]: " + coords[1]
        // X point coordinate, as in Cytomine
        def x = coords[0].toDouble()
        // Y point coordinate. Coordinate system in Cytomine is flipped in the y axis comparedto QuPath. 
        // Need to translate by calculating y-offset
        def y = imageHeight - coords[1].toDouble()
        
        // Add to list of QuPath points
        pointsList << new Point2(x, y)
    }
    pointsList
}

/**
 * Parses Cytomine's POLYGON WKT to QuPath PolygonROI
 * This includes Cytomine annoations: Polygon, Freehandpolygon, Circle and Rectangle,
 * as all are described as WKT POLYGON without further reference to the specific drawing tool
 */
def parsePolygon(pointsStr, imageHeight) {
    // Remove text strings from WKT
    pointsStr = pointsStr.minus("POLYGON ((").minus("))")
    //println pointsStr
    
    pointsList = parseWKTPoints(pointsStr, imageHeight)
    
    // Create roi from point list
    //println "Creating PolygonROI..."
    new PolygonROI(pointsList)
}

/**
 * Parses Cytomine's LINESTRING WKT to QuPath PolylineROI
 * This includes Cytomine annoations: Line and Freehandline,
 * as all are described as WKT POLYGON without further reference to the specific drawing tool
 */
def parseLine(pointsStr, imageHeight) {

    // Remove text strings from WKT
    pointsStr = pointsStr.minus("LINESTRING (").minus(")")
    //println pointsStr

    pointsList = parseWKTPoints(pointsStr, imageHeight)    

    // Create roi from point list
    //println "Creating PolylineROI..."
    new PolylineROI(pointsList, null)
}

/**
 * Parses Cytomine's POINT WKT to QuPath PointsROI
 * This includes Cytomine annoations: Point
 */
def parsePoint(pointsStr, imageHeight) {

    // Remove text strings from WKT
    pointsStr = pointsStr.minus("POINT (").minus(")")
    //println pointsStr

    def coords = pointsStr.trim().split(" ")
    def x = coords[0].toDouble()
    // Y point coordinate. Coordinate system in Cytomine is flipped in the y axis comparedto QuPath. 
    // Need to translate by calculating y-offset
    def y = imageHeight - coords[1].toDouble()

    // Create roi from point coordinates
    //println "Creating PointsROI..."
    new PointsROI(x, y)
}

def exportAnnots2JSON(jsonDir, projectName) {  
    
    def project = getProject()
    
    // Image and annotation data to be transformed in to Json
    def imagesAnnotsList = []	
    
    for (entry in project.getImageList()) {
    
        def filename = entry.getImageName()
        println "Processing image: " + filename
    
        def imageData = entry.readImageData()
        def server = imageData.getServer()
        def hierarchy = imageData.getHierarchy()
        
        def imageHeight = server.getHeight()                
        
        def annotsList = []
        def annotations = hierarchy.getAnnotationObjects()
        annotations.each { annot ->
            def annotClass = annot.getPathClass() 
            def annotTerms = annotClass.getName().split("-")
            Color col = new Color(annotClass.getColor())
            String annotColor = String.format("#%02X%02X%02X", col.getRed(), col.getGreen(), col.getBlue());
            //println annotTerms + " , " + annotColor
             
			
			def geom = annot.getROI().getGeometry()
			def wktWriter = new WKTWriter()
			
			geom.apply(new CoordinateSequenceFilter() {
				@Override
				public void filter(CoordinateSequence seq, int i) {
				 seq.setOrdinate(i, CoordinateSequence.Y, imageHeight - seq.getY(i));
				}
				@Override public boolean isDone() { return false; }
				@Override public boolean isGeometryChanged() { return true; }
			   });
			 
			
			def roiWKT = wktWriter.write(geom) 		
			//println "Orig WKT: " + roiWKT
			annotsList << [labels : annotTerms, color: annotColor, points : roiWKT]
	    
        }
        imagesAnnotsList << [ filename : filename, annotations : annotsList]                   
    }
    
    	// Json representation of image and annotation data
	boolean prettyPrint = true
        def gson = GsonTools.getInstance(prettyPrint)
	def jsonOut = gson.toJson(imagesAnnotsList)        
        	
	// Write Json to file
	def myFile = new File(jsonDir + projectName + '-QuPathAnnotations.json')
	myFile.write(jsonOut)
	println "\nQuPath annotations written as CytomJSON format to file: " + myFile.getCanonicalPath()
}

def importAnnots2QuPath(jsonDir, projectName) {

    // Variables to read Cytomines annotations in Json
    boolean prettyPrint = true
    def gson = GsonTools.getInstance(prettyPrint)
               
    // Path to Json annotations
    def filePath = jsonDir + projectName + "-CytomineAnnotations.json"
    Reader reader =  new File(filePath).newReader()
    
    // Read json annotations
    def imagesList = gson.fromJson(reader, List.class)
    //println "Json read from file"
    
    imagesList.each { annotsMap ->
            
        // Obtain file/image name for Json annotations
        def fileName = annotsMap['filename']
        println "Processing image: " + fileName
            
        def project = getProject()
        boolean imageFound = false
        def imageEntry = null
        def imageData = null
        for (anEntry in project.getImageList()) {
            //println entry.getImageName()
            if(anEntry.getImageName() == fileName) {
                //println "Json image found"
                // Open image
                imageData = anEntry.readImageData()
                imageEntry = anEntry
                imageFound = true
                break
            }
        }
        if(!imageFound) {
            println "**** Image from JSON not found in QuPath: " + fileName
        }
    
        // Obtain image height from server to calculate y-offset
        def server = imageData.getServer()
        def hierarchy = imageData.getHierarchy()
        
        def imageHeight = server.getHeight()                
        //println "Image height: " + imageHeight
        
        // Remove all eventual annotations in QuPath image
        hierarchy.clearAll()
            
        // Obtain and iterate oven Json annotations
        def annotsList = annotsMap['annotations']
        //println "Annotations: " + annotsList
        def qpathAnnots = []
        for(annot in annotsList) {
            // Annotation label. Concatenated if multiple terms associated with same annotation in Cytomine
            def label = annot['labels'].join('-')
            // Json/Cytomine point list, in WKT format - Currently only FREEHAND POLYGONS supported
            def pointsStr = annot['points']
            //println label + " : " + pointsStr
            
            def roi = null
            if (pointsStr.startsWith("POLYGON")) {
                roi = parsePolygon(pointsStr, imageHeight)
            } else if (pointsStr.startsWith("LINESTRING")) {
                roi = parseLine(pointsStr, imageHeight)
            } else if (pointsStr.startsWith("POINT")) {
                roi = parsePoint(pointsStr, imageHeight)
            }
            
            if(roi != null) {
                // Create annotation 
                def pathAnnotation = new PathAnnotationObject(roi)
                pathAnnotation.setName(label)
       	    
       	    // Set class as label and show class in gui   	    
       	    PathClass annotClass = PathClassFactory.getPathClass(label)
       	    if(annot['color']?.startsWith("#")) {
               	Color col = Color.decode(annot['color'])
               	annotClass.setColor(getColorRGB(col.getRed(), col.getGreen(), col.getBlue()))
               	//println "Changed PathClass color: " + annot['color']
    	    }	   	   
    	    
    	    pathAnnotation.setPathClass(annotClass)   
            //println "Created annotation: " + pathAnnotation
                
            // Add to list of QuPath annotations
            qpathAnnots << pathAnnotation
             } else {
                 println "**** Unable to parse annotation: " + label + " : " + pointsStr
             }
        }
        
        // Add QuPath annotations to image
        hierarchy.addPathObjects(qpathAnnots)    
        
        // Save annotations to QuPath image entry    
        imageEntry.saveImageData(imageData)
        //println "** Saved annotations to image: " + fileName
    }
    println "\nCytomJSON annotations read from file: " + filePath
}

// Main execution flow
if(importToQuPath1OrExportFromQuPath2 == 1) {
        println "Importing CytomJSON annotations into QuPath"
	importAnnots2QuPath(jsonDir, projectName)
} else if(importToQuPath1OrExportFromQuPath2 == 2) {
        println "Exporting QuPath annotations to CytomJSON"
	exportAnnots2JSON(jsonDir, projectName)
}

println "** Done"
