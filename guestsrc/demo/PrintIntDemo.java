package demo;

/** M8 object-links hang bisect: getBytes() on a LOADER String (literal) vs a WRITER String
 *  (from the linked Integer.toString). Output letters mark progress; literals are loader
 *  Strings so printing them is known-safe. */
public class PrintIntDemo
{
    public static void main(String[] args)
    {
        System.out.print("A");
        byte[] lb = "828".getBytes();                    // loader String -> known good
        System.out.print(lb.length == 3 ? "B" : "b");
        String ws = Integer.toString(828);               // LINKED -> writer-TIB String
        System.out.print("C");
        System.out.print(ws.length() == 3 ? "D" : "d");  // linked length() on it -> known good
        byte[] wb = ws.getBytes();                       // the suspect call
        System.out.print(wb.length == 3 ? "E" : "e");
        System.out.print("done");
    }
}
