# crforge


## How to run on Mac

On macOS, LibGDX with LWJGL3 requires a special JVM argument. To run from IntelliJ:

Add VM Option:                                                                                                                                                                                                                                                                    
- In the "VM options" field (you may need to click "Modify options" -> "Add VM options" to show it), add:                                                                                                                                                                           
`-XstartOnFirstThread`

Without -XstartOnFirstThread, macOS will crash with an error about AWT/LWJGL needing to run on the first thread.