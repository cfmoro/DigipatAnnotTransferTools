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
import com.google.gson.Gson
import com.google.gson.GsonBuilder


// Configurable variables for script functionality
// Set desired script function (mutually exclusive)
def import1OrExport2 = 2 // Script action/method
// Target images - if left empty, all images will be considered target
def targetImages = ["LVR-1-ck20_ck7.ndpi", "LVR-1-cd146_ngfr.ndpi"] // ["PKR-1-p53_d240_cald_maspin.ndpi", "PKR-1-muc5ac_muc6.ndpi"] //

def connectCytomine() {	
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
	be.cytomine.client.collections.Collection<Project> projects = be.cytomine.client.collections.Collection.fetch(Project.class)
	def myProject = null 
	for(int j = 0; j < projects.size(); j++) {
		Project aProject = projects.get(j)
		if(properties.projectName.equals(aProject.getStr("name"))) {
			myProject = aProject
			break
		}		
	}
	if(myProject == null) {
		throw(new Exception("**** Project not found in Cytomine: " + properties.projectName)) 
	}
	
	def myProjectName = myProject.getStr("name")
	println "Project Id: " + myProject.getId() + " name: " + myProjectName 
	
	// Obtain ontology and its terms for project
	Ontology ontology = new Ontology().fetch(myProject.getLong("ontology"))
	println "Ontology name: " +  ontology.getStr("name")
	
	// Retrieve terms in ontology - with fetchWithFilter using Project; using Ontology returns only leaf terms without children
	def terms = be.cytomine.client.collections.Collection.fetchWithFilter(Term.class, Project.class, myProject.getId())
	println "Retrieved ontology terms: " + terms
	
	// Retrieve all images in project
	def images = ImageInstanceCollection.fetchByProject(myProject)
	println "Retrieved project images: " + images + "\n"
	
	[myProject, images, ontology, terms]
}

def importAnnots2Cytomine(myProject, cytoImages, cytoOntology, cytoTerms) {
	def projectName = myProject.getStr("name")
			
	// Read annotations from JSON file
	Gson gson = new Gson()   
	// Path to Json annotations
	File jsonFile = new File('out/' + projectName + '-QuPathAnnotations' +  '.json')
	Reader reader =  jsonFile.newReader()
	
	def json = gson.fromJson(reader, List.class)	
	
	for(anImageAnnots in json) {
		def imageName = anImageAnnots['filename']
		println "Processing image: " + imageName
		
		// Obtain ref to Cytomine image
		def targetImage = null
		for (int i = 0; i < cytoImages.size(); i++) {
			ImageInstance anImage = cytoImages.get(i)
			if(anImage.getStr("filename") == imageName) {
				targetImage = anImage
				//println "Processing Cytomine image: " + targetImage
				break
			}
		}
		if(targetImage == null) {
			throw new Exception("**** Image not found in Cytomine: " + imageName)
		}
		// Remove all eventual annotations in Cytomine image
		// Find annotations for target image
		def parameters = [
			image: targetImage.getId(),
			showBasic: true,
			showTerm: true,
		]
		AnnotationCollection ac = AnnotationCollection.fetchWithParameters(parameters)
		//println "Obtained annotations for image: " + ac 
		for(int i = 0; i < ac.size(); i++) {
			Annotation anAnnot = ac.get(i)
			anAnnot.delete()
			//println "Previous annotation deleted in Cytomine"
		}
						
		def annotations = anImageAnnots['annotations']
		
		for(annot in annotations) {
			def annotNames = annot['labels']
			def annotationColor = annot['color']
			def roiWKT = annot['points']
			//println "   " + annotNames + " :" + annotationColor + " :" + roiWKT
			
			// Obtain and set Cytomine term(s) for annotation
			def termIds = []
			for(anAnnotName in annotNames) {
				def termFound = false
				for (int i = 0; i < cytoTerms.size(); i++) {
					Term aTerm = cytoTerms.get(i)
					if(aTerm.getStr("name") == anAnnotName) {
						//println "Found annot term: " + aTerm.getStr("name")
						termIds << aTerm.getId()
						termFound = true
						break
					}
				}
				if(!termFound) {
					println "** Annotation term not found in Cytomine: " + anAnnotName + ". Creating..."
					Term newTerm = new Term("test_" + anAnnotName, annotationColor, cytoOntology.getId()).save()
					//println "newTerm: " + newTerm.toJSON()
					termIds << newTerm.getId()
				}			
		  }
		  
		  if(termIds.size() == 0) { // This should not occur
			  new Exception("**** No terms found/created for annotation in image " + imageName)
		  }
		  
		  Annotation myAnnot = new Annotation(roiWKT, targetImage.getId(), termIds).save()
		  //println "** Created Cytomine annotation with terms: " + myAnnot.toJSON()
		}  	
	}	
	println "\nCytomJSON annotations read from file: " + jsonFile.getCanonicalPath()
}

def exportAnnots2JSON(myProject, images, ontology, terms, targetImages) {
	if(targetImages.size() == 0) {
		for (int i = 0; i < images.size(); i++) {
			ImageInstance anImage = images.get(i)
			targetImages << anImage.getStr("filename") 
		}
	}
	println "Configured targetImages: " + targetImages
	
	// Image and annotation data to be transformed in to Json
	def imagesAnnotsList = []
	targetImages.each { targetImage -> 
	
		// Find next target image
		println "Processing image: " + targetImage
		ImageInstance myImage = null
		def imageFound = false
		for (int i = 0; i < images.size(); i++) {
			myImage = images.get(i)
			//println(myImage.getStr("filename") + " id: " + myImage.getId() )
			if(myImage.getStr("filename") == targetImage) {
				//println("****** Image found")
				imageFound = true
				break
			}
		}
		if(!imageFound) {
			println "**** Target image not found: " + targetImage
			return
		}
		
		// To inspect object's attributes
		//println "Image attributes and values:" + myImage.toJSON()
		//println "Image attributes:" + myImage.attr.keySet()
		
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
		//println "Obtained annotations for image: " + ac
		
		Annotation annot = null
		for (int i = 0; i < ac.size(); i++) {
			annot = ac.get(i)
			//println "Obtained annotation nr " + i
			//println(annot.toJSON())
			//println(annot.getStr("location"))
					
			List annotTermsIds = annot.getList("term")
			//println "Obtained term ids for annotation" +  annotTermsIds
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
					throw new Exception("**** Failure to find Term in Cytomine, term ID: " + targetTermId)
				}
				 
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
		imagesAnnotsList << [ filename : targetImage, annotations : annotsList]
	}
	
	// Json representation of image and annotation data
	//def jsonOut = new JsonBuilder(imagesAnnotsList).toPrettyString()
	// Json representation of image and annotation data
	Gson gson = new GsonBuilder().setPrettyPrinting().create()
	def jsonOut = gson.toJson(imagesAnnotsList)
	//println "JSON output:"
	//println jsonOut	
	// Write Json to file
	def myFile = new File('out/' + myProject.getStr("name") + '-CytomineAnnotations.json')
	myFile.write(jsonOut)
	println "\nCytomine JSON annotations written to file: " + myFile.getCanonicalPath()
}

// Main execution flow
def (myProject, images, ontology, terms) = connectCytomine()

if(import1OrExport2 == 1) {
	println "Importing JSON annotations to Cytomine..."
	importAnnots2Cytomine(myProject, images, ontology, terms)
} else if(import1OrExport2 == 2) { 
	println "Exporting JSON annotations from Cytomine..."
	exportAnnots2JSON(myProject, images, ontology, terms, targetImages)
}

println "** Done"
