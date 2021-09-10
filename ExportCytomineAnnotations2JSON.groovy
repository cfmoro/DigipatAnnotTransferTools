// Script by Carlos Fernandez Moro
import be.cytomine.client.Cytomine
import be.cytomine.client.collections.AnnotationCollection
import be.cytomine.client.collections.ImageInstanceCollection
import be.cytomine.client.collections.TermCollection
import be.cytomine.client.models.Annotation
import be.cytomine.client.models.ImageInstance
import be.cytomine.client.models.Ontology
import be.cytomine.client.models.Project
import be.cytomine.client.models.Term

@Grab("org.codehaus.groovy:groovy-json")
import groovy.json.JsonBuilder

// Retrieve Cytomine host credentials from separate properties file 
Properties properties = new Properties()
File propertiesFile = new File('config/CytomineConn.properties')
propertiesFile.withInputStream {
	properties.load(it)
}

Cytomine.connection(properties.host, properties.publicKey, properties.privateKey)
println "Connection established to Cytomine host: " + properties.host
// We are now connected!

// It will print your username, that has been retrieved from Cytomine.
println "Cytomine user: " + Cytomine.getInstance().getCurrentUser().get("username")

// Obtain project
def myProject = new Project().fetch(Long.parseLong(properties.myProjectId))
def myProjectName = myProject.getStr("name")
println "Project Id: " + properties.myProjectId + " name: " + myProjectName 

// Obtain ontology and its terms for project
Ontology ontology = new Ontology().fetch(myProject.getLong("ontology"))
println "Ontology name: " +  ontology.getStr("name")

// Retrieve terms in ontology - with fetchWithFilter using Project; using Ontology returns only leaf terms without children
def terms = be.cytomine.client.collections.Collection.fetchWithFilter(Term.class, Project.class, myProject.getId())
println "Retrieved ontology terms: " + terms

// Retrieve all images in project
def images = ImageInstanceCollection.fetchByProject(myProject)
println "Retrieved project images: " + images

// Target images - if left empty, all images will be considered target
def targetImages = ["PKR-1-p53_d240_cald_maspin.ndpi", "PKR-1-muc5ac_muc6.ndpi"] //  "LVR-1-p53_cd34_cald_ck7.ndpi", "LVR-1-cd3_cd20.ndpi"
if(targetImages.size() == 0) {
	for (int i = 0; i < images.size(); i++) {
		ImageInstance anImage = images.get(i)
		targetImages << anImage.getStr("filename") 
	}
}
println "Target images: " + targetImages

// Image and annotation data to be transformed in to Json
def imagesAnnotsMap = []
targetImages.each { targetImage -> 

	// Find next target image
	println "Will export annotations for image: " + targetImage
	ImageInstance myImage = null
	def imageFound = false
	for (int i = 0; i < images.size(); i++) {
		myImage = images.get(i)
		//println(myImage.getStr("filename") + " id: " + myImage.getId() )
		if(myImage.getStr("filename") == targetImage) {
			println("****** Image found")
			imageFound = true
			break
		}
	}
	if(!imageFound) {
		println "Target image not found: " + targetImage
		return
	}
	
	// To inspect object's attributes
	println "Image attributes and values:" + myImage.toJSON()
	println "Image attributes:" + myImage.attr.keySet()
	def imageHeight = myImage.getLong("height")
	println "Image height:" + imageHeight 
	
	// List of annotations
	def annotsList = []
	
	// Find annotations for target image
	def parameters = [
		image: myImage.getId(),
		showBasic: true,
		showTerm: true,
		showWKT: true
	]
	AnnotationCollection ac = AnnotationCollection.fetchWithParameters(parameters)
	println "Obtained annotations for image: " + ac
	
	Annotation annot = null
	for (int i = 0; i < ac.size(); i++) {
		annot = ac.get(i)
		println "Obtained annotation nr " + i
		println(annot.toJSON())
		//println(annot.getStr("location"))
				
		List annotTermsIds = annot.getList("term")
		println "Obtained term ids for annotation" +  annotTermsIds
		def annotTerms = []
		def color = null
		for (int j = 0; j < annotTermsIds.size(); j++) {
			Long targetTermId = (Long)annotTermsIds.get(j)
			Term targetTerm = null
			for (int k = 0; k < terms.size(); k++) {
				Term currentTerm = terms.get(k)
				if (currentTerm.getId() == targetTermId) {
					targetTerm = currentTerm 
					break
				}
			}
			if(targetTerm == null) {
				println "************ Term not found in Cytomine, term ID: " + targetTermId
				return 
			}
			
			// Alt 2
			//Term targetTerm = new Term().fetch(targetTermId)
			//println "Obtained term: " + targetTerm.getStr("name") 
			annotTerms << targetTerm.getStr("name")
			if(color == null) {
				color = targetTerm.getStr("color")
			}
		}
		// In case of missing associated terms
		if(annotTerms.size() == 0) {
			annotTerms << "Empty label"
		}
		annotsList << [labels : annotTerms, color: color, points : annot.getStr("location")]
	}	
	imagesAnnotsMap << [ filename : targetImage, imageHeight: imageHeight, annotations : annotsList]
}

// Json representation of image and annotation data
def jsonOut = new JsonBuilder(imagesAnnotsMap).toPrettyString()
//println "JSON output:"
//println jsonOut

// Write Json to file
def myFile = new File('out/CytomineAnnotations' + myProjectName + '.json')
myFile.write(jsonOut)
println "*** Done"