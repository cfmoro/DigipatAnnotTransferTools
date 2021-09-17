// Original script by Egor Zindy (https://forum.image.sc/t/exporting-ndpi-ndpa-annotation-files-from-qupath-code-attached/55418)
// Adapted and modified by Carlos Fernandez Moro
import groovy.xml.MarkupBuilder
import org.openslide.OpenSlide
import qupath.lib.scripting.QP
import qupath.lib.gui.tools.ColorToolsFX
import qupath.lib.geom.Point2
import qupath.lib.images.servers.ImageServer
import qupath.lib.common.GeneralTools
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.classes.PathClass
import qupath.lib.objects.classes.PathClassFactory
import qupath.lib.roi.*
import org.locationtech.jts.geom.util.PolygonExtracter
import org.locationtech.jts.simplify.TopologyPreservingSimplifier
import org.locationtech.jts.io.WKTWriter
import java.awt.Color

// Configurable variables for script functionality
// Recommended workflow: Summary without label mapping > summary with label mapping > import with label mapping
// Set desired script function (mutually exclusive)
def summary1OrImport2OrExport3 = 3

// Wether labels specified in labelMapping (above) or the original Ndpa labels - only for import to Cytomine
def useLabelMapping = true

// Map of Ndpa labels to QuPath classes and names
def labelMapping = [
    t: "test_Tumor",
    M: "Celltypes-IPMN",
    T: "Normal_DE",
    s: "Ductal cancer",
    L: "IPMN", 
    B: "High grade",
    pp: "Low grade"
]

def get_osr(server) {
	//def server = QP.getCurrentImageData().getServer()

	// If OpenSlide metadata isn't available, load it up!
	if (server.hasProperty('osr') && server.osr){
		// Image was opened with OpenSlide
		osr = server.osr
	} else {
		// Code borrowed from qupath/qupath-extension-openslide/src/main/java/qupath/lib/images/servers/openslide/OpenslideImageServer.java
		// Ensure the garbage collector has run - otherwise any previous attempts to load the required native library
		// from different classloader are likely to cause an error (although upon first further investigation it seems this doesn't really solve the problem...)
		System.gc();
		def uri = GeneralTools.toPath(server.getURIs()[0]).toString();
		file = new File(uri);
		osr = new OpenSlide(file);
	}
	return osr
}

// Convert a point from NDPA to QuPath coordinates
def convertPoint(point, pixelWidthNm=1, pixelHeightNm=1, OffSet_From_Top_Left_X=0, OffSet_From_Top_Left_Y=0) {
	point.x = (point.x.toDouble() / pixelWidthNm) + OffSet_From_Top_Left_X
	point.y = (point.y.toDouble() / pixelHeightNm) + OffSet_From_Top_Left_Y
}

def getNdpa(server) {
	def path = GeneralTools.toPath(server.getURIs()[0]).toString()+".ndpa"
	new File(path)
}

def getPixelSizeNm(server) {
	// We need the pixel size
	def cal = server.getPixelCalibration()
	if (!cal.hasPixelSizeMicrons()) {
		Dialogs.showMessageDialog("Metadata check", "No pixel information for this image!");
		return
	}

	def pixelWidthNm = cal.getPixelWidthMicrons() * 1000
	def pixelHeightNm = cal.getPixelHeightMicrons() * 1000
	//println "Cal: " + cal
	
	[pixelWidthNm, pixelHeightNm]
}

def getXYOffsets(server) {

	def ImageCenter_X = server.getWidth()/2
	def ImageCenter_Y = server.getHeight()/2
	//println "Image width": + server.getWidth() + " .Image height: " + server.getHeight()
	//println "ImageCenter_X:" + ImageCenter_X + " .ImageCenter_Y: " + ImageCenter_Y

	def osr = get_osr(server)
	//println "osr: "  + osr
	//println "osr: "  + osr.getLevel0Height()
	//println "osr: "  + osr.getLevel0Width()
	//println "Obtained pixel size x-nm:" + osr.properties."openslide.mpp-x"
	
	//Get X Reference from OPENSLIDE data
	//The Open slide numbers are actually offset from IMAGE center (not physical slide center).
	//This is annoying, but you can calculate the value you need -- Offset from top left in Nanometers.
	OffSet_From_Top_Left_X = ImageCenter_X
	OffSet_From_Top_Left_Y = ImageCenter_Y
	
	// Obtain pixel size
	def (pixelWidthNm, pixelHeightNm) = getPixelSizeNm(server)	

	osr.getProperties().each { k, v ->
			// println k + ": " + v
		if(k.equals("hamamatsu.XOffsetFromSlideCentre")){
			OffSet_From_Top_Left_X -= v.toDouble()/pixelWidthNm
		}
		if(k.equals("hamamatsu.YOffsetFromSlideCentre")){
			OffSet_From_Top_Left_Y -= v.toDouble()/pixelHeightNm
		}
	}
	
	[OffSet_From_Top_Left_X, OffSet_From_Top_Left_Y]
}

// Create QuPath annotations from Ndpa files
def importAnnots2QuPath(entry, useLabelMapping, labelMapping) {
	
	def imageData = entry.readImageData()
	def server = imageData.getServer()
	// need to add annotations to hierarchy so qupath sees them
	def hierarchy = imageData.getHierarchy()

	// Remove all eventual annotations in QuPath image
	hierarchy.clearAll()

	// Get NDPA automatically based on naming scheme
	def NDPAfile = getNdpa(server)
	if (!NDPAfile.exists()) {
		println "******** No NDPA file for this image..."
		return
	}

	// Obtain Ndpa specific coordinate offsets
	def (OffSet_From_Top_Left_X, OffSet_From_Top_Left_Y) = getXYOffsets(server)
	// Obtain pixel size
	def (pixelWidthNm, pixelHeightNm) = getPixelSizeNm(server)

	//Read files
	// Supported annotation types: Freehand polygon, freehand line, circle, rectangle, pin (point)
	def text = NDPAfile.getText()
	def list = new XmlSlurper().parseText(text)

	// List containing the imported QuPath annotations
	def qpathAnnots = []

	list.ndpviewstate.each { ndpviewstate ->
		def annotationName = ndpviewstate.title.toString().trim()
		def annotationClassName = annotationName
		def annotationType = ndpviewstate.annotation.@type.toString().toUpperCase()
		def annotationColor = ndpviewstate.annotation.@color.toString().toUpperCase()

		def details = ndpviewstate.details.toString()
		//println annotationName+" ("+annotationType+") ("+annotationClassName+") "+details
				
		roi = null

		if (annotationType == "CIRCLE") {
			//special case
			def point = new Point2(ndpviewstate.annotation.x.toDouble(), ndpviewstate.annotation.y.toDouble())
			convertPoint(point, pixelWidthNm, pixelHeightNm, OffSet_From_Top_Left_X, OffSet_From_Top_Left_Y)

			def rx = ndpviewstate.annotation.radius.toDouble() / pixelWidthNm
			def ry = ndpviewstate.annotation.radius.toDouble() / pixelHeightNm
			roi = new EllipseROI(point.x-rx,point.y-ry,rx*2,ry*2,null);
		}

		if (annotationType == "PIN") {
			def point = new Point2(ndpviewstate.annotation.x.toDouble(), ndpviewstate.annotation.y.toDouble())
			convertPoint(point, pixelWidthNm, pixelHeightNm, OffSet_From_Top_Left_X, OffSet_From_Top_Left_Y)
			roi = new PointsROI(point.x,point.y)
			annotationColor = "#00FF00"
		}

		// All that's left if FREEHAND which handles polygons, polylines, rectangles
		ndpviewstate.annotation.pointlist.each { pointlist ->
			def tmp_points_list = []
			pointlist.point.each{ point ->
				X = point.x.toDouble()
				Y =  point.y.toDouble()
				tmp_points_list.add(new Point2(X, Y))
			}

			//Adjust each point relative to SLIDECENTER coordinates and adjust for pixelsPerMicron
			for ( point in tmp_points_list){
				convertPoint(point, pixelWidthNm, pixelHeightNm, OffSet_From_Top_Left_X, OffSet_From_Top_Left_Y)
			}

			if (annotationType == "FREEHAND") {
				def isClosed = ndpviewstate.annotation.closed.toBoolean()
				def isRectangle = ndpviewstate.annotation.specialtype.toString().startsWith("rectangle")
				if (isRectangle) {
					x1 = tmp_points_list[0].x
					y1 = tmp_points_list[0].y
					x3 = tmp_points_list[2].x
					y3 = tmp_points_list[2].y
					roi = new RectangleROI(x1,y1,x3-x1,y3-y1);
				}
				else if (isClosed) {
					//println "Point list: " + tmp_points_list
					roi = new PolygonROI(tmp_points_list);
				} else
					roi = new PolylineROI(tmp_points_list, null);
			}

		}

		if (roi != null) {
                        // Label mapping
        		if(useLabelMapping) {
        		    if(!labelMapping.containsKey(annotationName) || 
        				!(labelMapping[annotationName] instanceof CharSequence) || labelMapping[annotationName].length() < 1) {
        		         throw new Exception("**** Invalid label mapping for Ndpa label: " + annotationName)
        		     }
        		     annotationName = labelMapping[annotationName]
        		}
    				
			def annotation = new PathAnnotationObject(roi)
			annotation.setName(annotationName)

			// Set class as label and show class in gui
			//println "annotationColor: " + annotationColor
			Color col = Color.decode(annotationColor)
			PathClass annotClass = PathClassFactory.getPathClass(annotationName)
			annotClass.setColor(getColorRGB(col.getRed(), col.getGreen(), col.getBlue()))

			annotation.setPathClass(annotClass)

			if (details) {
				annotation.setDescription(details)
			}

			annotation.setLocked(true)
			// Add to list of QuPath annotations
			qpathAnnots << annotation

			def wktWriter = new WKTWriter()
			//println wktWriter.write(roi.getGeometry())

		} else {
			println "Unable to parse annotation: " + annotationName+" ("+annotationType+") ("+annotationClassName+")"
		}
	}
	// Add QuPath annotations to image
	hierarchy.addPathObjects(qpathAnnots)

	// Save annotations to QuPath image entry
	entry.saveImageData(imageData)
	println "Saved annotations to image: " + entry.getImageName() + "\n"
}


/*
 *  Summarize Ndpa files and annotations
 *
 *  @entry - An image entry in a QuPath project
 */
def summarizeNdpa(entry, ndpaNames, annotNames, annotTypes, useLabelMapping, labelMapping, wrongAnnots) {

	def imageData = entry.readImageData()
	def server = imageData.getServer()

	// Get NDPA automatically based on naming scheme
	def NDPAfile = getNdpa(server)
	if (!NDPAfile.exists()) {
		println "******** No NDPA file for this image..."
		return
	}
	ndpaNames << NDPAfile.getName()
		
	//Read files
	def text = NDPAfile.getText()
	def list = new XmlSlurper().parseText(text)

	list.ndpviewstate.each { ndpviewstate ->
		def annotationName = ndpviewstate.title.toString().trim()
		//println "annotationName: "  + annotationName
		def annotationType = ndpviewstate.annotation.@type.toString().toUpperCase()
		if(ndpviewstate.annotation.specialtype.toString().startsWith("rectangle")) {
			annotationType = "RECTANGLE"
		}
		//println "annotationType: "  + annotationType

		// Annotation names/labels
		// New annotation label, initialize counter
		if(!annotationType.equals("POINTER") && !annotationName.contains("%")) {  // Skip custom detail for % viable tumor as label O:)
						
		
			// Label counter
			if(!annotNames.containsKey(annotationName)) {
				annotNames[(annotationName)] = 1
			} else {
				annotNames[(annotationName)] = annotNames[(annotationName)] + 1
			}
		}

		// Annotation types counter
		if(!annotTypes.containsKey(annotationType)) {
			annotTypes[(annotationType)] = 1
		} else {
			annotTypes[(annotationType)] = annotTypes[(annotationType)] + 1
		}

	}
}

/**
 * Print summary of images and NDPAs in QuPath project
 * @param imgNames
 * @param annots
 * @param annotTypes
 * @return
 */
def printSummary(imgNames, ndpaNames, annotNames, annotTypes, wrongAnnots) {
	println "\nSummary of images and NDPAs in QuPath project"
	println "============================================="
	println "Number images (ndpi): " + imgNames.size()
	println "Number annotation files (ndpa): " + ndpaNames.size()
	println "Total number of annotations: " + (annotNames.values().sum() + wrongAnnots.size())
	println "Annotation labels and counts: " + annotNames
	println "Annotation types and counts: " + annotTypes
	println "Wrong annotations:" + wrongAnnots.size()
	for(aWAnnot in wrongAnnots) {
		println "\t" + aWAnnot[0] + "\tid: " + aWAnnot[1]
	}

}

//Here we extract the polygon coords
def getPointList(ring) {
    def pointlist = []
    coords = ring.getCoordinates()
    coords[0..<coords.length-1].each { coord ->
        pointlist.add([coord.x, coord.y])
    }
    //println "---"
    return pointlist
}

def exportAnnots2Ndpa(entry) {
	
	def imageData = entry.readImageData()
	def server = imageData.getServer()
	// need to add annotations to hierarchy so qupath sees them
	def hierarchy =  imageData.getHierarchy()
	def pathObjects = hierarchy.getAnnotationObjects()
	
	// Get NDPA automatically based on standard naming scheme
	def NDPAfile = getNdpa(server) 
        
        // Obtain image center coords
	def ImageCenter_X = server.getWidth()/2
	def ImageCenter_Y = server.getHeight()/2
	// Obtain Ndpa specific coordinate offsets
	def (OffSet_From_Top_Left_X, OffSet_From_Top_Left_Y) = getXYOffsets(server)
	// Obtain pixel size
	def (pixelWidthNm, pixelHeightNm) = getPixelSizeNm(server)
	
	//create a list of annotations
	def list_annot = []
	def ndpIndex = 0

	pathObjects.each { pathObject ->
	        //println "Processing annot: " + pathObject.getPathClass().getName()
		//We make a list of polygons, each has an exterior and interior rings
		geometry = pathObject.getROI().getGeometry()

		//Here we do some processing to simplify the outlines and remove small holes
		geometry = TopologyPreservingSimplifier.simplify(geometry, 5.0);
		geometry = GeometryTools.refineAreas(geometry, 200, 200)
		var polygons = PolygonExtracter.getPolygons(geometry);

		polygons.each { polygon ->

                        //println "Processing polygon"
			//here we create a list of rings, we'll need to treat the first one differently
			def rings = [ polygon.getExteriorRing() ]
			def nRings = polygon.getNumInteriorRing();
			for (int i = 0; i < nRings; i++) {
				var ring = polygon.getInteriorRingN(i);
				rings.add(ring)
			}

			rings.eachWithIndex { ring, index ->
				def annot = [:]
				if (index == 0) {
					annot['title'] = pathObject.getName()
					annot['details'] = pathObject.getPathClass()
					annot['color'] = '#' + Integer.toHexString(ColorToolsFX.getDisplayedColorARGB(pathObject)).substring(2)
					isFirst = false
				} else {
					annot['title'] = "clear"
					annot['details'] = "clear"
					annot['color'] = '#000000'
				}

				annot['id'] = ++ndpIndex
				annot['coordformat'] = 'nanometers'
				annot['lens'] = 0.445623
				annot['x'] = ImageCenter_X.toInteger()
				annot['y'] = ImageCenter_Y.toInteger()
				annot['z'] = 0
				annot['showtitle'] = 0
				annot['showhistogram'] = 0
				annot['showlineprofile'] = 0
				annot['type'] = 'freehand'
				annot['displayname'] = 'AnnotateFreehand'
				annot['measuretype'] = 0
				annot['closed'] = 1

				//add the point list
				annot['pointlist'] = getPointList(ring)
				list_annot.add(annot)
			}
		}
	}

	//make an XML string
	def writer = new StringWriter()
	def xml = new MarkupBuilder(writer)
	xml.mkp.xmlDeclaration(version: "1.0", encoding: "utf-8", standalone: "yes")
	xml.annotations {
		list_annot.each { annot ->
			ndpviewstate('id':annot['id']) {
				title(annot['title'])
				details(annot['details'])
				coordformat(annot['coordformat'])
				lens(annot['lens'])
				x(annot['x'])
				y(annot['y'])
				z(annot['z'])
				showtitle(annot['showtitle'])
				showhistogram(annot['showhistogram'])
				showlineprofile(annot['showlineprofile'])

				//Annotation object
				annotation(type:annot['type'], displayname:annot['displayname'], color:annot['color']) {
					measuretype(annot['measuretype'])
					closed(annot['closed'])
					pointlist {
						annot['pointlist'].each { pt ->
							point {
								x( ((pt[0] - OffSet_From_Top_Left_X ) * pixelWidthNm).toInteger() )
								y( ((pt[1] - OffSet_From_Top_Left_Y ) * pixelHeightNm).toInteger() )
							}
						}
					}
				}
			}
		}
	}
	NDPAfile.write(writer.toString())
	println "Annotations written to NDPA file: " + NDPAfile.getCanonicalPath() + "\n"
}


// Main work flow
// Iterate over project images and read their ndpas
def project = getProject()

if(summary1OrImport2OrExport3 == 1) { // Do summary
        println "Performing summary of NDPA annotations"
        
	def imgNames = []
	def ndpaNames = []
	// Map of annotation labels and counts in ndpas
	def annotNames = [:]
	// Map of annotation types and counts in ndpas
	def annotTypes = [:]
	def wrongAnnots = []
	for (entry in project.getImageList()) {
		println "Reading NDPA files for summary: " +  entry.getImageName()
		imgNames << entry.getImageName()
		summarizeNdpa(entry, ndpaNames, annotNames, annotTypes, useLabelMapping, labelMapping, wrongAnnots)
	}
	printSummary(imgNames, ndpaNames, annotNames, annotTypes, wrongAnnots)
} else if(summary1OrImport2OrExport3 == 2) { // Import annotations
        println "Importing NDPA annotations into QuPath"
        
	for (entry in project.getImageList()) {
		print "Importing annotations for image: " +  entry.getImageName()
		importAnnots2QuPath(entry, useLabelMapping, labelMapping)
	}
} else if(summary1OrImport2OrExport3 == 3) { // Export annotations
        println "Exporting QuPath annotations to NDPA"
        
	for (entry in project.getImageList()) {
		print "Processing image: " +  entry.getImageName()
		exportAnnots2Ndpa(entry)
	}	
}

println "\n** Done"
