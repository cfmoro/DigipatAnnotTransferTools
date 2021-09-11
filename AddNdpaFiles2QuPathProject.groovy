// Script by Egor Zindy
// https://forum.image.sc/t/creating-project-from-command-line/45608/2
// TODO - does not work in v0.3

import groovy.io.FileType
import java.awt.image.BufferedImage
import qupath.lib.images.servers.ImageServerProvider
import qupath.lib.gui.commands.ProjectCommands

selectedDir = Dialogs.promptForDirectory(null)
//selectedDir = new File("Path/to/your/image/folder")

if (selectedDir == null)
    return
    
//Check if we already have a QuPath Project directory in there...
projectName = "QuPathProject"
File directory = new File(selectedDir.toString() + File.separator + projectName)

if (!directory.exists())
{
    print("No project directory, creating one!")
    directory.mkdirs()
}

// Create project
def project = Projects.createProject(directory , BufferedImage.class)

// Build a list of files
def files = []
selectedDir.eachFileRecurse (FileType.FILES) { file ->
    if (file.getName().toLowerCase().endsWith(".ndpi"))
    {
        files << file
        print(file.getCanonicalPath())      
    }
}

// Add files to the project
for (file in files) {
    def imagePath = file.getCanonicalPath()
    
    // Get serverBuilder
    def support = ImageServerProvider.getPreferredUriImageSupport(BufferedImage.class, imagePath, "")
    def builder = support.builders.get(0)

    // Make sure we don't have null 
    if (builder == null) {
       print "Image not supported: " + imagePath
       continue
    }
    
    // Add the image as entry to the project
    print "Adding: " + imagePath
    entry = project.addImage(builder)
    
    // Set a particular image type
    def imageData = entry.readImageData()
    imageData.setImageType(ImageData.ImageType.BRIGHTFIELD_H_DAB)
    entry.saveImageData(imageData)
    
    // Write a thumbnail if we can
    var img = ProjectCommands.getThumbnailRGB(imageData.getServer());
    entry.setThumbnail(img)
    
    // Add an entry name (the filename)
    entry.setImageName(file.getName())
}

// Changes should now be reflected in the project directory
project.syncChanges()
