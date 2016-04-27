import socket

NUMBER_OF_TURNS = 2


class ChanceItClient:
    '''
       ChanceIt Test Client
    '''

    def __init__(self, sock=None):
        if sock is None:
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        else:
            self.sock = sock

    def connect(self, host, port):
        self.sock.connect((host, port))

    def send(self, msg):
        totalsent = 0
        MSGLEN = len(msg)
        while totalsent < MSGLEN - 1:
            sent = self.sock.send(msg[totalsent:])
            if sent == 0:
                raise RuntimeError("socket connection broken")
            totalsent = totalsent + sent

    def readLine(self, msg=""):
        '''
            Read a char at a time up to a '\n'
            This is inefficient however it is simple than alternatives
        '''
        line = ""
        while True:
            chunk = self.sock.recv(1)
            if chunk == "" or chunk == "\n":
                break
            line += chunk
        return line


if __name__ == "__main__":
    client = ChanceItClient()
    client.connect('localhost', 1099)

    # identify yourself
    client.send("HELLO:McLovin\n")

    # assert you get confirmation with the "IS IT ME YOUR LOOKING FOR"
    data = client.readLine()
    print data
    if data.startswith("IS IT ME YOU'RE LOOKIN FOR?"):
        print "got HELLO response"
    else:
        print data

    # assert you get Welcome "name"
    data = client.readLine()
    print data
    if data.startswith("WELCOME "):
        print "got Welcome response"
    else:
        print data

    # assert you get the OPPONENT message
    data = client.readLine()
    print "data is: ###\n%s\n###" % data
    if data.startswith("Opponent: "):
        # get your opponent
        opponent = data[len("Opponent: "):]
        print "Opponent is: %s" % opponent

    # get this player's first roll
    data = client.readLine()
    if data.startswith("Your roll was: "):
        # get your starting roll
        roll = data[len("Your roll was: "):]
        print "roll was: %s" % roll

    # print "entering turn loop"
    turnNumber = rollNumber = totalScore = score = 0
    while (True):
        '''
            Turn: 1
            Roll: 2
            Score: 16-38
            Turn Score: 23
            chance-it?

            or

            Turn: 1
            Roll: 2
            Score: 16-38
            Turn Score: 23
            chance-it?

        '''
        data = client.readLine()
        if data.startswith("Turn: "):
            turnNumber = int(data[len("Turn: "):])
            print "_turn: %s" % turnNumber
        else:
            '''
                If both players roll the same on the first roll
                A subsequent roll will happen.
                Though unlikely, this can potentially occur more than once.
            '''
            while data.startswith("Your roll was: "):
                data = client.readLine()

            if data.startswith("Turn: "):
                turnNumber = int(data[len("Turn: "):])
                print "_turn: %s" % turnNumber

        data = client.readLine()
        if data.startswith("Roll: "):
            rollNumber = int(data[len("Roll: "):])
            print "_roll: %s" % rollNumber

        data = client.readLine()
        if data.startswith("Score: "):
            totalScore = int(data[len("Score: "):data.find("-")])
            print "_total score: %s __total score: %s" % (totalScore, data)

        data = client.readLine()
        if data.startswith("Turn Score: "):
            score = int(data[len("Turn Score: "):])
            print "_score: %s" % score

        '''
            When score == 0 we've rolled the same value as our first roll for this turn.
            The turn is finised and the total score for this turn is 0.
            else we can roll again by sending 'chance-it' to the server
        '''
        if 0 == score and turnNumber == NUMBER_OF_TURNS:
            data = client.readLine()
            print data
            break
        elif 0 != score:
            # we did not roll the same value as the first roll for this turn :)
            # assert we get the 'chance-it?' prompt
            data = client.readLine()
            print "## %s" % data
            if data.startswith("chance-it?"):
                # depending on what you want to do, chance-it
                if score < 30:
                    print "# chance-it"
                    client.send("chance-it\n")
                else:
                    print "# stop"
                    client.send("stop\n")
                    if turnNumber == NUMBER_OF_TURNS:
                        # the game is over so block on a read from the socket to learn who won
                        data = client.readLine()
                        print data
                        break
